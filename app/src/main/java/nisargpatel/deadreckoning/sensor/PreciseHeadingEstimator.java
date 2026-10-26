package nisargpatel.deadreckoning.sensor;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

public class PreciseHeadingEstimator {
    
    private static final float MAG_WEIGHT = 0.02f;
    private static final float ALPHA = 0.96f;
    
    private float[] gravityValues = null;
    private float[] magValues = null;
    private float[] gyroValues = null;
    
    private double gyroHeading = 0;
    private double magHeading = 0;
    private double filteredHeading = 0;
    private boolean gyroHeadingInitialized = false;
    private boolean headingFilterInitialized = false;
    private boolean rotationVectorAvailable = false;
    
    private long lastTimestamp = 0;
    
    private KalmanFilter headingKalman;
    private float[] rotationMatrix = new float[9];
    private float[] orientationAngles = new float[3];
    
    public PreciseHeadingEstimator() {
        headingKalman = new KalmanFilter(0.05, 0.05, 0, 1);
    }
    
    public void updateGravity(float[] values) {
        this.gravityValues = values;
    }
    
    public void updateMagneticField(float[] values) {
        this.magValues = values;
    }

    /** Prefer Android's fused rotation vector when the device provides it. */
    public void updateRotationVector(float[] values) {
        if (values == null || values.length < 3) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        magHeading = orientationAngles[0];
        rotationVectorAvailable = true;
    }
    
    public void updateGyroscope(float[] values, long timestamp) {
        if (lastTimestamp == 0) {
            lastTimestamp = timestamp;
            gyroValues = values.clone();
            initializeGyroHeadingFromCompass();
            return;
        }
        
        float dt = (timestamp - lastTimestamp) / 1_000_000_000.0f;
        lastTimestamp = timestamp;
        
        gyroValues = values.clone();

        if (!gyroHeadingInitialized) {
            initializeGyroHeadingFromCompass();
        }
        
        double gyroHeadingDelta = -gyroValues[2] * dt;
        gyroHeading += gyroHeadingDelta;
        gyroHeading = wrapRadians(gyroHeading);
    }
    
    public void updateGyroscope(SensorEvent event) {
        updateGyroscope(event.values, event.timestamp);
    }
    
    public double getHeading() {
        if (!rotationVectorAvailable) {
            if (gravityValues != null && magValues != null) {
                calculateMagnetometerHeading();
            }

            if (gyroValues != null) {
                if (!gyroHeadingInitialized) {
                    initializeGyroHeadingFromCompass();
                } else if (gravityValues != null && magValues != null) {
                    // Apply the compass correction along the shortest angular path.
                    gyroHeading = wrapRadians(gyroHeading
                            + (1 - ALPHA) * wrapRadians(magHeading - gyroHeading));
                }
                filteredHeading = gyroHeading;
            } else {
                filteredHeading = magHeading;
            }
        } else {
            // The platform rotation-vector sensor already fuses accelerometer,
            // gyroscope and magnetometer data into a device orientation estimate.
            filteredHeading = magHeading;
        }
        
        if (!headingFilterInitialized) {
            headingKalman.reset(filteredHeading, 1);
            headingFilterInitialized = true;
        }
        double currentEstimate = headingKalman.getEstimate();
        double nearestMeasurement = currentEstimate + wrapRadians(filteredHeading - currentEstimate);
        double kalmanHeading = headingKalman.update(nearestMeasurement);
        
        return (Math.toDegrees(kalmanHeading) + 360.0) % 360.0;
    }

    private void initializeGyroHeadingFromCompass() {
        if (gravityValues == null || magValues == null) return;
        calculateMagnetometerHeading();
        gyroHeading = magHeading;
        gyroHeadingInitialized = true;
    }

    private static double wrapRadians(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
    
    private void calculateMagnetometerHeading() {
        boolean success = SensorManager.getRotationMatrix(
            rotationMatrix, null, gravityValues, magValues
        );
        
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            magHeading = orientationAngles[0];
        }
    }
    
    public double getHeadingDegrees() {
        return getHeading();
    }
    
    public void reset() {
        gyroHeading = 0;
        magHeading = 0;
        filteredHeading = 0;
        lastTimestamp = 0;
        gyroHeadingInitialized = false;
        headingFilterInitialized = false;
        rotationVectorAvailable = false;
        headingKalman.reset(0, 1);
    }
    
    public float getAccuracy() {
        return gravityValues != null && magValues != null ? 1.0f : 0.0f;
    }
    
    public boolean hasValidData() {
        return gravityValues != null && magValues != null;
    }

    public float[] getGravity() {
        return gravityValues;
    }

    public float[] getMagneticField() {
        return magValues;
    }

    public float[] getGyroscope() {
        return gyroValues;
    }
}
