package com.example.noisesense;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity implements SensorEventListener, LocationListener {
    private static final String TAG = "NoiseSense";

    // Permissions
    private static final int PERMISSION_REQUEST_CODE = 1234;

    // Audio parameters
    private AudioRecord recorder = null;
    private Thread recordingThread = null;
    private boolean isRecording = false;
    private int bufferSize = 0;
    private final int sampleRate = 44100; // safe on modern phones

    // Calibration: convert device reading to approximate SPL. Adjust after calibration with reference meter.
    private static final double CALIBRATION_OFFSET_DB = 0.0;

    // Nuisance threshold (default).
    private static final double NUISANCE_THRESHOLD_DB = 65.0; // e.g., day-time community threshold

    // Accelerometer
    private SensorManager sensorManager;
    private Sensor accel;
    private volatile float accelMagnitude = 0f;
    private static final float MOTION_IGNORE_THRESHOLD = 3.5f; // m/s^2 (approx). If phone shaken, we may ignore readings.

    // Location
    private LocationManager locationManager;
    private volatile Location currentLocation = null;

    // UI
    private TextView tvDb, tvStatus, tvAccel, tvLocation;
    private Button btnStartStop;

    // Handler for UI updates
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDb = findViewById(R.id.tvDb);
        tvStatus = findViewById(R.id.tvStatus);
        tvAccel = findViewById(R.id.tvAccel);
        tvLocation = findViewById(R.id.tvLocation);
        btnStartStop = findViewById(R.id.btnStartStop);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleRecording();
            }
        });

        requestPermissionsIfNeeded();
    }

    private void requestPermissionsIfNeeded() {
        String[] perms = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        boolean need = false;
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
        } else {
            // already have perms
            registerSensorsAndLocation();
        }
    }

    private void registerSensorsAndLocation() {
        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
        }
        // request location updates
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                Criteria c = new Criteria();
                c.setAccuracy(Criteria.ACCURACY_COARSE);
                String provider = locationManager.getBestProvider(c, true);
                if (provider == null) provider = LocationManager.GPS_PROVIDER;
                locationManager.requestLocationUpdates(provider, 2000, 5, this);
                Location last = locationManager.getLastKnownLocation(provider);
                if (last != null) currentLocation = last;
                updateLocationUi();
            } catch (Exception e) {
                Log.w(TAG, "Location registration failed", e);
            }
        }
    }

    private void toggleRecording() {
        if (isRecording) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = sampleRate * 2;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission.
            return;
        }
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            return;
        }

        recorder.startRecording();
        isRecording = true;
        btnStartStop.setText("Stop");

        recordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readAudioBuffer();
            }
        }, "AudioRecorder Thread");
        recordingThread.start();
    }

    private void stopRecording() {
        if (recorder != null) {
            isRecording = false;
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {}
            recorder.release();
            recorder = null;
            recordingThread = null;
        }
        btnStartStop.setText("Start");
    }

    private void readAudioBuffer() {
        short[] buffer = new short[bufferSize];
        while (isRecording) {
            int read = recorder.read(buffer, 0, buffer.length);
            if (read > 0) {
                double sumSq = 0;
                for (int i = 0; i < read; i++) {
                    double v = buffer[i] / 32768.0; // normalized to -1..1
                    sumSq += v * v;
                }
                double rms = Math.sqrt(sumSq / read);
                // convert to dBFS
                double dbFS = 20 * Math.log10(rms + 1e-9); // avoid log(0)
                // Convert to approximate SPL by adding calibration offset and scaling:
                // This is an approximation; real SPL needs microphone calibration.
                double approxDb = dbFS + CALIBRATION_OFFSET_DB + 90.0;

                final double finalDb = approxDb;

                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleNewDbReading(finalDb);
                    }
                });
            }
        }
    }


    private void handleNewDbReading(double db) {
        // If device is being shaken/vibrated strongly, mark as motion and ignore evaluation
        boolean heavyMotion = accelMagnitude > MOTION_IGNORE_THRESHOLD;
        tvDb.setText(String.format("%.1f dB", db));
        tvAccel.setText(String.format("Motion: %.2f m/s²", accelMagnitude));

        if (heavyMotion) {
            tvStatus.setText("Phone moving — reading ignored");
            tvStatus.setBackgroundColor(getResources().getColor(android.R.color.holo_orange_light));
            return;
        }

        if (db >= NUISANCE_THRESHOLD_DB) {
            tvStatus.setText("NUISANCE — Exceeds threshold");
            tvStatus.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));
        } else {
            tvStatus.setText("OK — Below threshold");
            tvStatus.setBackgroundColor(getResources().getColor(android.R.color.holo_green_light));
        }
    }

    // SensorEventListener
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float ax = sensorEvent.values[0];
            float ay = sensorEvent.values[1];
            float az = sensorEvent.values[2];
            accelMagnitude = (float) Math.sqrt(ax*ax + ay*ay + az*az);
            // gravity ~9.8 m/s^2 included; consider subtracting gravity if you want linear acceleration
            tvAccel.setText(String.format("Motion: %.2f m/s²", accelMagnitude));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) { }

    // LocationListener
    @Override
    public void onLocationChanged(@NonNull Location location) {
        currentLocation = location;
        updateLocationUi();
    }

    private void updateLocationUi() {
        if (currentLocation != null) {
            String s = String.format("Lat: %.5f\nLng: %.5f\nAlt: %.1f m\nProvider: %s",
                    currentLocation.getLatitude(),
                    currentLocation.getLongitude(),
                    currentLocation.hasAltitude() ? currentLocation.getAltitude() : 0.0,
                    currentLocation.getProvider());
            tvLocation.setText(s);
        } else {
            tvLocation.setText("Location: unknown");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (accel != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {}
        }
    }

    // Permission results
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean ok = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            }
            if (ok) {
                registerSensorsAndLocation();
            } else {
                // permission denied — inform user (simple log here)
                Log.w(TAG, "Required permissions denied");
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // Unused LocationListener methods
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
}
