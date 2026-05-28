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

    private static final float DEFAULT_STEP_LENGTH_M = 0.7f;
    private static final double WIFI_CORRECTION_ALPHA = 0.65;

    private final SensorManager sensorManager;
    private final Listener listener;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    private Sensor rotationSensor;
    private Sensor stepDetectorSensor;
    private boolean tracking;
    private boolean hasHeading;
    private boolean hasPosition;
    private Integer floor;
    private double x;
    private double y;
    private double headingRadians;
    private float stepLengthM = DEFAULT_STEP_LENGTH_M;

    PdrTracker(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
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
    }

    void stop() {
        if (sensorManager == null || !tracking) {
            return;
        }
        sensorManager.unregisterListener(this);
        tracking = false;
    }

    boolean isAvailable() {
        return rotationSensor != null && stepDetectorSensor != null;
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
            advanceOneStep();
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
        x += Math.sin(headingRadians) * stepCells;
        y += Math.cos(headingRadians) * stepCells;
        if (listener != null) {
            listener.onPdrPositionChanged(currentState());
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
