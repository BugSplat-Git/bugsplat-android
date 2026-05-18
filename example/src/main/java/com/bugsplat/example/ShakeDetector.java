package com.bugsplat.example;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public final class ShakeDetector implements SensorEventListener {

    public interface Listener {
        void onShake();
    }

    // Tuned for a deliberate phone shake, not a pocket jostle.
    private static final float SHAKE_THRESHOLD_G = 2.7f;
    private static final int MIN_SHAKE_EVENTS = 3;
    private static final long SHAKE_WINDOW_MS = 1_000L;
    private static final long COOLDOWN_MS = 2_000L;

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Listener listener;

    private int shakeCount;
    private long firstShakeAtMs;
    private long lastFiredAtMs;

    public ShakeDetector(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.listener = listener;
    }

    public void start() {
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        shakeCount = 0;
        firstShakeAtMs = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float gx = event.values[0] / SensorManager.GRAVITY_EARTH;
        float gy = event.values[1] / SensorManager.GRAVITY_EARTH;
        float gz = event.values[2] / SensorManager.GRAVITY_EARTH;
        float gForce = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);

        if (gForce <= SHAKE_THRESHOLD_G) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastFiredAtMs < COOLDOWN_MS) {
            return;
        }
        if (shakeCount == 0 || now - firstShakeAtMs > SHAKE_WINDOW_MS) {
            firstShakeAtMs = now;
            shakeCount = 1;
            return;
        }
        shakeCount++;
        if (shakeCount >= MIN_SHAKE_EVENTS) {
            lastFiredAtMs = now;
            shakeCount = 0;
            firstShakeAtMs = 0;
            listener.onShake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }
}
