package nisargpatel.deadreckoning.sensor;

import android.location.Location;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.model.Trip;
import nisargpatel.deadreckoning.model.TurnEvent;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.stepcounting.DynamicStepCounter;
import nisargpatel.deadreckoning.stepcounting.StaticStepCounter;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;

public class DeadReckoningEngine {
    
    private final EnhancedStepCounter stepCounter;
    private final PreciseHeadingEstimator headingEstimator;
    private final GPSCalibrator gpsCalibrator;
    
    private double currentX = 0;
    private double currentY = 0;
    private double currentHeading = 0;
    private double previousHeading = 0;
    private double totalDistance = 0;
    private double travelHeadingOffset = 0;
    
    private boolean isRunning = false;
    private Location startLocation = null;
    private Location lastGPSLocation = null;
    
    private Trip currentTrip = null;
    private final List<TurnEvent> turnEvents = new ArrayList<>();
    
    private static final double TURN_THRESHOLD = 25.0;
    private static final int MIN_STEPS_BETWEEN_TURNS = 5;
    private int stepsSinceLastTurn = 0;
    private double accumulatedHeadingChange = 0;
    private boolean isTurning = false;
    
    private int androidStepCount = 0;
    private int staticStepCount = 0;
    private StepCounterPreferences.StepMode stepMode = StepCounterPreferences.StepMode.DYNAMIC;
    
    private DynamicStepCounter dynamicStepCounter;
    private StaticStepCounter staticStepCounter;
    
    public DeadReckoningEngine() {
        stepCounter = new EnhancedStepCounter();
        headingEstimator = new PreciseHeadingEstimator();
        gpsCalibrator = new GPSCalibrator();
        
        dynamicStepCounter = new DynamicStepCounter(0.875);
        staticStepCounter = new StaticStepCounter(2, 1.9);
    }
    
    public void setStepMode(StepCounterPreferences.StepMode mode) {
        if (isRunning && stepMode == StepCounterPreferences.StepMode.ANDROID
                && mode == StepCounterPreferences.StepMode.DYNAMIC) {
            // Dynamic detection runs in parallel as a safety net while the hardware
            // detector is selected. Commit those steps once when hardware is silent.
            int fallbackSteps = stepCounter.getStepCount();
            stepMode = mode;
            for (int i = 0; i < fallbackSteps; i++) {
                updatePosition();
                stepsSinceLastTurn++;
            }
            return;
        }
        this.stepMode = mode;
    }
    
    public void start() {
        isRunning = true;
        stepCounter.reset();
        staticStepCounter.clearStepCount();
        androidStepCount = 0;
        headingEstimator.reset();
        gpsCalibrator.resetCalibration();
        currentTrip = new Trip();
        turnEvents.clear();
        useManualHeading = false;
        
        currentX = 0;
        currentY = 0;
        currentHeading = 0;
        previousHeading = 0;
        totalDistance = 0;
        travelHeadingOffset = 0;
        accumulatedHeadingChange = 0;
        stepsSinceLastTurn = 0;
        isTurning = false;
        startLocation = null;
        lastGPSLocation = null;
    }
    
    public void stop() {
        isRunning = false;
        
        if (currentTrip != null) {
            currentTrip.setTotalDistance(totalDistance);
            currentTrip.setTotalSteps(getStepCount());
            currentTrip.setTurnEvents(new ArrayList<>(turnEvents));
            currentTrip.finish();
        }
    }

    public void resetCalibration() {
        gpsCalibrator.resetCalibration();
    }

    /** Seeds a trip from the most recent known fix when a fresh GPS fix is unavailable. */
    public void setInitialLocation(Location location) {
        if (location == null) return;
        startLocation = new Location(location);
        lastGPSLocation = new Location(location);
        if (currentTrip != null && currentTrip.getPathPoints().isEmpty()) {
            currentTrip.addPoint(new GeoPoint(location.getLatitude(), location.getLongitude()));
        }
    }

    /** Start a new local dead-reckoning segment at the last confirmed GPS fix. */
    public void reanchorAtLocation(Location location) {
        if (location == null) return;
        startLocation = new Location(location);
        lastGPSLocation = new Location(location);
        currentX = 0;
        currentY = 0;

        if (currentTrip != null) {
            GeoPoint anchor = new GeoPoint(location.getLatitude(), location.getLongitude());
            List<GeoPoint> points = currentTrip.getPathPoints();
            if (points.isEmpty() || points.get(points.size() - 1).distanceToAsDouble(anchor) > 2.0) {
                currentTrip.addPoint(anchor);
            }
        }
    }
    
    public void reset() {
        currentX = 0;
        currentY = 0;
        currentHeading = 0;
        previousHeading = 0;
        totalDistance = 0;
        travelHeadingOffset = 0;
        stepCounter.reset();
        headingEstimator.reset();
        gpsCalibrator.resetCalibration();
        
        currentTrip = null;
        turnEvents.clear();
        
        accumulatedHeadingChange = 0;
        stepsSinceLastTurn = 0;
        isTurning = false;
        
        startLocation = null;
        lastGPSLocation = null;
    }
    
    public void updateSensors(float[] gravity, float[] magnetic, float[] gyro, float[] linearAccel, long timestamp) {
        if (!isRunning) return;
        
        if (gravity != null) {
            headingEstimator.updateGravity(gravity);
        }
        if (magnetic != null) {
            headingEstimator.updateMagneticField(magnetic);
        }
        if (gyro != null) {
            headingEstimator.updateGyroscope(gyro, timestamp);
        }
        
        if (gravity != null && magnetic != null) {
            previousHeading = currentHeading;
            if (!useManualHeading) {
                currentHeading = headingEstimator.getHeadingDegrees();
            }
            detectTurn();
        }
        
        if (linearAccel != null) {
            double magnitude = Math.sqrt(
                linearAccel[0] * linearAccel[0] +
                linearAccel[1] * linearAccel[1] +
                linearAccel[2] * linearAccel[2]
            );
            
            boolean dynamicDetected = stepCounter.detectStep(linearAccel, timestamp);
            boolean staticDetected = staticStepCounter.findStep(magnitude);
            boolean detected = false;
            
            switch (stepMode) {
                case DYNAMIC:
                    detected = dynamicDetected;
                    break;
                case STATIC:
                    detected = staticDetected;
                    break;
                case ANDROID:
                    detected = false;
                    break;
            }
            
            if (detected) {
                updatePosition();
                stepsSinceLastTurn++;
            }
        }
    }

    public void updateRotationVector(float[] rotationVector) {
        if (!isRunning || rotationVector == null) return;
        headingEstimator.updateRotationVector(rotationVector);
        if (!useManualHeading) {
            previousHeading = currentHeading;
            currentHeading = headingEstimator.getHeadingDegrees();
            detectTurn();
        }
    }
    
    public void addAndroidStep() {
        if (!isRunning) return;
        
        androidStepCount++;
        
        if (stepMode == StepCounterPreferences.StepMode.ANDROID) {
            updatePosition();
            stepsSinceLastTurn++;
        }
    }
    
    private void detectTurn() {
        double headingDelta = currentHeading - previousHeading;
        
        if (Math.abs(headingDelta) > 180) {
            if (headingDelta > 0) {
                headingDelta -= 360;
            } else {
                headingDelta += 360;
            }
        }
        
        if (Math.abs(headingDelta) > 5 && !isTurning) {
            isTurning = true;
            accumulatedHeadingChange = 0;
        }
        
        if (isTurning) {
            accumulatedHeadingChange += headingDelta;
            
            if (Math.abs(accumulatedHeadingChange) >= TURN_THRESHOLD && stepsSinceLastTurn >= MIN_STEPS_BETWEEN_TURNS) {
                TurnEvent.TurnType turnType = TurnEvent.determineTurnType(accumulatedHeadingChange);
                
                if (turnType != null && Math.abs(accumulatedHeadingChange) < 270) {
                    addTurnEvent(turnType, accumulatedHeadingChange);
                }
                
                isTurning = false;
                accumulatedHeadingChange = 0;
                stepsSinceLastTurn = 0;
            } else if (Math.abs(accumulatedHeadingChange) < 5) {
                isTurning = false;
                accumulatedHeadingChange = 0;
            }
        }
    }
    
    private void addTurnEvent(TurnEvent.TurnType type, double headingChange) {
        double lat = 0, lon = 0;
        
        if (currentTrip != null && !currentTrip.getPathPoints().isEmpty()) {
            GeoPoint lastPoint = currentTrip.getPathPoints().get(currentTrip.getPathPoints().size() - 1);
            lat = lastPoint.getLatitude();
            lon = lastPoint.getLongitude();
        }
        
        TurnEvent event = new TurnEvent(type, headingChange, stepCounter.getStepCount(), lat, lon);
        turnEvents.add(event);
        
        if (currentTrip != null) {
            currentTrip.addTurnEvent(event);
        }
    }
    
    private void updatePosition() {
        double strideLength = stepCounter.getStrideLength() * gpsCalibrator.getScaleFactor();
        double correctedHeading = gpsCalibrator.correctHeading(currentHeading + travelHeadingOffset);
        double headingRadians = Math.toRadians(correctedHeading);
        
        double deltaX = strideLength * Math.sin(headingRadians);
        double deltaY = strideLength * Math.cos(headingRadians);
        
        currentX += deltaX;
        currentY += deltaY;
        totalDistance += strideLength;
        
        if (currentTrip != null) {
            GeoPoint point = calculateGeoPoint(currentX, currentY);
            if (point != null) {
                currentTrip.addPoint(point);
            }
        }
    }
    
    private GeoPoint calculateGeoPoint(double x, double y) {
        if (startLocation == null) return null;

        final double earthRadius = 6_371_000.0;
        double distance = Math.hypot(x, y);
        double bearing = Math.atan2(x, y);
        double angularDistance = distance / earthRadius;
        double startLat = Math.toRadians(startLocation.getLatitude());
        double startLon = Math.toRadians(startLocation.getLongitude());

        double endLat = Math.asin(Math.sin(startLat) * Math.cos(angularDistance)
                + Math.cos(startLat) * Math.sin(angularDistance) * Math.cos(bearing));
        double endLon = startLon + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(startLat),
                Math.cos(angularDistance) - Math.sin(startLat) * Math.sin(endLat));
        endLon = (endLon + 3 * Math.PI) % (2 * Math.PI) - Math.PI;

        return new GeoPoint(Math.toDegrees(endLat), Math.toDegrees(endLon));
    }
    
    public void calibrateWithGPS(Location location) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 20) {
            return;
        }
        
        if (startLocation == null) {
            startLocation = location;
            lastGPSLocation = location;
            
            if (currentTrip != null) {
                currentTrip.addPoint(new GeoPoint(location.getLatitude(), location.getLongitude()));
            }
            return;
        }
        
        gpsCalibrator.addCalibrationPoint(
            location,
            currentX,
            currentY,
            totalDistance
        );
        
        lastGPSLocation = location;
    }
    
    public double getX() {
        return currentX;
    }
    
    public double getY() {
        return currentY;
    }
    
    public double getHeading() {
        return gpsCalibrator.correctHeading(currentHeading);
    }

    /** Adjust the displacement bearing without changing the phone's displayed heading. */
    public void setTravelHeadingOffset(double offsetDegrees) {
        travelHeadingOffset = offsetDegrees;
    }
    
    public int getStepCount() {
        switch (stepMode) {
            case DYNAMIC:
                return stepCounter.getStepCount();
            case STATIC:
                return staticStepCounter.getStepCount();
            case ANDROID:
                return androidStepCount;
            default:
                return stepCounter.getStepCount();
        }
    }
    
    public int getDynamicStepCount() {
        return stepCounter.getStepCount();
    }
    
    public int getStaticStepCount() {
        return staticStepCounter.getStepCount();
    }
    
    public int getAndroidStepCount() {
        return androidStepCount;
    }
    
    public double getDistance() {
        return totalDistance;
    }
    
    public double getDistanceFromGPS(Location gpsLocation) {
        if (startLocation == null || gpsLocation == null) {
            return -1;
        }
        return startLocation.distanceTo(gpsLocation);
    }
    
    public boolean isCalibrated() {
        return gpsCalibrator.isCalibrated();
    }
    
    public int getCalibrationPoints() {
        return gpsCalibrator.getCalibrationPointCount();
    }
    
    public void setStrideLength(double meters) {
        stepCounter.setStrideLength(meters);
    }
    
    public double getStrideLength() {
        return stepCounter.getStrideLength();
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public Location getStartLocation() {
        return startLocation;
    }
    
    public Location getLastGPSLocation() {
        return lastGPSLocation;
    }
    
    public double getScaleFactor() {
        return gpsCalibrator.getScaleFactor();
    }
    
    public Trip getCurrentTrip() {
        return currentTrip;
    }
    
    public List<TurnEvent> getTurnEvents() {
        return new ArrayList<>(turnEvents);
    }
    
    public boolean isTurning() {
        return isTurning;
    }
    
    public double getAccumulatedHeadingChange() {
        return accumulatedHeadingChange;
    }

    private static final double MANUAL_TURN_ANGLE = 90.0;
    private boolean useManualHeading = false;
    private double manualHeadingOffset = 0;

    public void turnLeft() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading -= MANUAL_TURN_ANGLE;
        
        while (currentHeading < 0) {
            currentHeading += 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.LEFT, -MANUAL_TURN_ANGLE);
    }

    public void turnRight() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading += MANUAL_TURN_ANGLE;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.RIGHT, MANUAL_TURN_ANGLE);
    }

    public void turnAround() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading += 180;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.UTURN, 180);
    }

    private void addManualTurnEvent(TurnEvent.TurnType type, double angle) {
        double lat = 0, lon = 0;
        
        if (currentTrip != null && !currentTrip.getPathPoints().isEmpty()) {
            GeoPoint lastPoint = currentTrip.getPathPoints().get(currentTrip.getPathPoints().size() - 1);
            lat = lastPoint.getLatitude();
            lon = lastPoint.getLongitude();
        }
        
        TurnEvent event = new TurnEvent(type, angle, stepCounter.getStepCount(), lat, lon);
        turnEvents.add(event);
        
        if (currentTrip != null) {
            currentTrip.addTurnEvent(event);
        }
    }
    
    public void setFixedHeading(double heading) {
        currentHeading = heading;
        useManualHeading = true;
    }
    
    public void resetToCompassHeading() {
        useManualHeading = false;
    }
}
