package com.example.wifinavigation;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

final class PdrTracker implements SensorEventListener {
    interface Listener {
        void onPdrPositionChanged(State state);
    }

    private static final float DEFAULT_STEP_LENGTH_M = 0.525f;
    private static final double WIFI_CORRECTION_ALPHA = 0.60;
    private static final double DEFAULT_HEADING_OFFSET_DEGREES = -30.0;
    private static final float ACCEL_STEP_THRESHOLD = 1.2f;
    private static final long ACCEL_STEP_MIN_INTERVAL_NS = 320_000_000L;
    private static final long STEP_DETECTOR_STALE_NS = 2_000_000_000L;

    private final SensorManager sensorManager;
    private final Listener listener;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];

    private Sensor rotationSensor;
    private Sensor stepDetectorSensor;
    private Sensor accelerometerSensor;
    private boolean tracking;
    private boolean hasHeading;
    private boolean hasPosition;
    private Integer floor;
    private double x;
    private double y;
    private double headingRadians;
    private float stepLengthM = DEFAULT_STEP_LENGTH_M;
    private float lastAccelMagnitude;
    private long lastAccelStepTimeNanos;
    private long lastStepDetectorTimeNanos;
    private double headingOffsetDegrees = DEFAULT_HEADING_OFFSET_DEGREES;

    PdrTracker(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    void start() {
        if (sensorManager == null || tracking) {
            return;
        }
        tracking = true;
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (stepDetectorSensor != null) {
            sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    void stop() {
        if (sensorManager == null || !tracking) {
            return;
        }
        sensorManager.unregisterListener(this);
        tracking = false;
    }

    boolean isAvailable() {
        return rotationSensor != null && (stepDetectorSensor != null || accelerometerSensor != null);
    }

    double getHeadingOffsetDegrees() {
        return headingOffsetDegrees;
    }

    void setHeadingOffsetDegrees(double headingOffsetDegrees) {
        this.headingOffsetDegrees = headingOffsetDegrees;
    }

    State correctWithWifi(Integer wifiFloor, double wifiX, double wifiY) {
        if (wifiFloor == null) {
            return null;
        }
        if (!hasPosition || floor == null || !floor.equals(wifiFloor)) {
            floor = wifiFloor;
            x = wifiX;
            y = wifiY;
            hasPosition = true;
        } else {
            x = x + WIFI_CORRECTION_ALPHA * (wifiX - x);
            y = y + WIFI_CORRECTION_ALPHA * (wifiY - y);
        }
        return currentState();
    }

    State currentState() {
        if (!hasPosition || floor == null) {
            return null;
        }
        return new State(floor, x, y, hasHeading, isAvailable());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            headingRadians = orientation[0];
            hasHeading = true;
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            lastStepDetectorTimeNanos = event.timestamp;
            advanceOneStep();
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            maybeDetectStepFromAccelerometer(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void advanceOneStep() {
        if (!hasPosition || !hasHeading) {
            return;
        }
        double stepCells = stepLengthM / WifiPositioning.CELL_SIZE_M;
        double correctedHeading = headingRadians + Math.toRadians(headingOffsetDegrees);
        x += Math.sin(correctedHeading) * stepCells;
        y += Math.cos(correctedHeading) * stepCells;
        if (listener != null) {
            listener.onPdrPositionChanged(currentState());
        }
    }

    private void maybeDetectStepFromAccelerometer(SensorEvent event) {
        if (stepDetectorSensor != null
                && event.timestamp - lastStepDetectorTimeNanos < STEP_DETECTOR_STALE_NS) {
            return;
        }

        final float smoothing = 0.8f;
        gravity[0] = smoothing * gravity[0] + (1f - smoothing) * event.values[0];
        gravity[1] = smoothing * gravity[1] + (1f - smoothing) * event.values[1];
        gravity[2] = smoothing * gravity[2] + (1f - smoothing) * event.values[2];

        float ax = event.values[0] - gravity[0];
        float ay = event.values[1] - gravity[1];
        float az = event.values[2] - gravity[2];
        float magnitude = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        boolean risingPeak = lastAccelMagnitude <= ACCEL_STEP_THRESHOLD && magnitude > ACCEL_STEP_THRESHOLD;
        boolean spacedEnough = event.timestamp - lastAccelStepTimeNanos > ACCEL_STEP_MIN_INTERVAL_NS;
        lastAccelMagnitude = magnitude;

        if (risingPeak && spacedEnough) {
            lastAccelStepTimeNanos = event.timestamp;
            advanceOneStep();
        }
    }

    static final class State {
        final int floor;
        final double x;
        final double y;
        final boolean hasHeading;
        final boolean sensorsAvailable;

        State(int floor, double x, double y, boolean hasHeading, boolean sensorsAvailable) {
            this.floor = floor;
            this.x = x;
            this.y = y;
            this.hasHeading = hasHeading;
            this.sensorsAvailable = sensorsAvailable;
        }
    }
}
