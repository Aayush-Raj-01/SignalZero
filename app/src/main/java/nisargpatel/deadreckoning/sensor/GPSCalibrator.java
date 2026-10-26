package nisargpatel.deadreckoning.sensor;

import android.location.Location;
public class GPSCalibrator {

    private boolean isCalibrated = false;
    private int calibrationCount = 0;
    private double scaleFactorX = 1.0;
    private double scaleFactorY = 1.0;
    private double headingBias = 0.0;
    private Location previousGpsLocation;
    private double previousEstimatedX;
    private double previousEstimatedY;
    private double previousEstimatedPathDistance;
    private double accumulatedGpsDistance;
    private double accumulatedEstimatedDistance;

    public static final int MIN_CALIBRATION_POINTS = 3;

    /** Learn a conservative stride correction from GPS and step distance over time. */
    public void addCalibrationPoint(Location gpsLocation, double estimatedX, double estimatedY,
                                    double estimatedPathDistance) {
        if (gpsLocation == null || !gpsLocation.hasAccuracy() || gpsLocation.getAccuracy() > 15f) return;

        if (previousGpsLocation == null) {
            remember(gpsLocation, estimatedX, estimatedY, estimatedPathDistance);
            return;
        }

        double elapsedSeconds = (gpsLocation.getTime() - previousGpsLocation.getTime()) / 1000.0;
        double gpsSegment = gpsLocation.distanceTo(previousGpsLocation);
        double estimatedSegment = estimatedPathDistance - previousEstimatedPathDistance;
        double estimatedXSegment = estimatedX - previousEstimatedX;
        double estimatedYSegment = estimatedY - previousEstimatedY;
        double estimatedVectorDistance = Math.hypot(estimatedXSegment, estimatedYSegment);

        if (elapsedSeconds <= 0 || elapsedSeconds > 5 || gpsSegment > 15 || estimatedSegment < 0) {
            accumulatedGpsDistance = 0;
            accumulatedEstimatedDistance = 0;
        } else if (estimatedSegment >= 0.25) {
            accumulatedGpsDistance += gpsSegment;
            accumulatedEstimatedDistance += estimatedSegment;

            if (estimatedVectorDistance >= 2.5 && gpsSegment >= 2.5 && elapsedSeconds <= 3) {
                double gpsBearing = previousGpsLocation.bearingTo(gpsLocation);
                double estimatedBearing = Math.toDegrees(Math.atan2(estimatedXSegment, estimatedYSegment));
                double difference = wrapDegrees(gpsBearing - estimatedBearing);
                headingBias = clamp(headingBias * 0.95 + difference * 0.05, -30, 30);
            }
        }

        // Average over several metres so GPS jitter or a single delayed step cannot
        // change the stride estimate dramatically.
        if (accumulatedEstimatedDistance >= 5.0) {
            double ratio = accumulatedGpsDistance / accumulatedEstimatedDistance;
            if (accumulatedGpsDistance >= 2.5 && ratio >= 0.65 && ratio <= 1.35) {
                double updatedScale = clamp(scaleFactorX * 0.8 + ratio * 0.2, 0.75, 1.25);
                scaleFactorX = updatedScale;
                scaleFactorY = updatedScale;
                calibrationCount++;
                isCalibrated = calibrationCount >= MIN_CALIBRATION_POINTS;
            }
            accumulatedGpsDistance = 0;
            accumulatedEstimatedDistance = 0;
        }

        remember(gpsLocation, estimatedX, estimatedY, estimatedPathDistance);
    }

    private void remember(Location location, double x, double y, double pathDistance) {
        previousGpsLocation = new Location(location);
        previousEstimatedX = x;
        previousEstimatedY = y;
        previousEstimatedPathDistance = pathDistance;
    }

    private static double wrapDegrees(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public double[] correctPosition(double estimatedX, double estimatedY, double heading) {
        // Scale is applied to each new step by DeadReckoningEngine. Applying it to
        // the cumulative position on every GPS callback compounds the correction.
        return new double[]{estimatedX, estimatedY};
    }
    
    public double correctHeading(double heading) {
        if (!isCalibrated) return heading;
        
        double corrected = heading + headingBias;
        while (corrected > 180) corrected -= 360;
        while (corrected < -180) corrected += 360;
        
        return corrected;
    }
    
    public void resetCalibration() {
        previousGpsLocation = null;
        previousEstimatedX = 0;
        previousEstimatedY = 0;
        previousEstimatedPathDistance = 0;
        accumulatedGpsDistance = 0;
        accumulatedEstimatedDistance = 0;
        isCalibrated = false;
        calibrationCount = 0;
        scaleFactorX = 1.0;
        scaleFactorY = 1.0;
        headingBias = 0.0;
    }
    
    public boolean isCalibrated() {
        return isCalibrated;
    }
    
    public int getCalibrationPointCount() {
        return calibrationCount;
    }
    
    public Location getLastKnownLocation() {
        return previousGpsLocation == null ? null : new Location(previousGpsLocation);
    }
    
    public double getScaleFactor() {
        return (scaleFactorX + scaleFactorY) / 2.0;
    }
}
