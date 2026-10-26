package nisargpatel.deadreckoning.sensor;

import java.util.ArrayList;
import java.util.List;

public class EnhancedStepCounter {
    
    private static final int WINDOW_SIZE = 10;
    // Detect peaks above a slowly moving baseline so phones with different sensor
    // offsets can use the same step detector.
    private static final double STEP_THRESHOLD_HIGH = 0.35;
    private static final double STEP_RELEASE_RATIO = 0.45;
    private static final double STEP_RELEASE_FLOOR = 0.08;
    private static final double BASELINE_ADAPTATION = 0.02;
    private static final long STEP_MIN_TIME_NANOS = 300_000_000L;
    
    private List<Double> accelerationWindow = new ArrayList<>();
    private List<Long> timestampWindow = new ArrayList<>();
    
    private int stepCount = 0;
    private long lastStepTime = 0;
    private boolean isPeakFound = false;
    private double peakValue = 0;
    private double accelerationBaseline = 0;
    private boolean baselineInitialized = false;
    private double currentStrideLength = 0.75;
    
    private KalmanFilter kalmanFilter;
    private double lastAcceleration = 0;
    
    public EnhancedStepCounter() {
        kalmanFilter = new KalmanFilter(0.01, 0.1, 0, 1);
    }
    
    public boolean detectStep(float[] linearAcceleration, long timestamp) {
        double magnitude = Math.sqrt(
            linearAcceleration[0] * linearAcceleration[0] +
            linearAcceleration[1] * linearAcceleration[1] +
            linearAcceleration[2] * linearAcceleration[2]
        );
        
        double filteredAcc = kalmanFilter.update(magnitude);
        if (!baselineInitialized) {
            accelerationBaseline = filteredAcc;
            baselineInitialized = true;
        }
        double stepSignal = filteredAcc - accelerationBaseline;
        accelerationBaseline += BASELINE_ADAPTATION * stepSignal;
        
        accelerationWindow.add(filteredAcc);
        timestampWindow.add(timestamp);
        
        if (accelerationWindow.size() > WINDOW_SIZE) {
            accelerationWindow.remove(0);
            timestampWindow.remove(0);
        }
        
        return analyzeForStep(stepSignal, timestamp);
    }
    
    private boolean analyzeForStep(double stepSignal, long timestamp) {
        if (!isPeakFound && stepSignal > STEP_THRESHOLD_HIGH
                && timestamp - lastStepTime >= STEP_MIN_TIME_NANOS) {
            isPeakFound = true;
            peakValue = stepSignal;
            lastStepTime = timestamp;
            stepCount++;
            return true;
        }

        if (isPeakFound) {
            if (stepSignal > peakValue) {
                peakValue = stepSignal;
            } else if (timestamp - lastStepTime >= STEP_MIN_TIME_NANOS
                    && stepSignal < Math.max(STEP_RELEASE_FLOOR, peakValue * STEP_RELEASE_RATIO)) {
                // Re-arm from the fall after each impact. A fixed near-zero threshold
                // can stay latched on phones whose linear-acceleration sensor has bias.
                isPeakFound = false;
                peakValue = 0;
            }
        }
        
        return false;
    }
    
    public void setStrideLength(double strideLengthMeters) {
        this.currentStrideLength = strideLengthMeters;
    }
    
    public double getStrideLength() {
        return currentStrideLength;
    }
    
    public int getStepCount() {
        return stepCount;
    }
    
    public void reset() {
        stepCount = 0;
        lastStepTime = 0;
        isPeakFound = false;
        peakValue = 0;
        accelerationBaseline = 0;
        baselineInitialized = false;
        accelerationWindow.clear();
        timestampWindow.clear();
        kalmanFilter.reset(0, 1);
    }
    
    public double getDistanceTraveled() {
        return stepCount * currentStrideLength;
    }
    
    public double getCurrentAcceleration() {
        if (accelerationWindow.isEmpty()) return 0;
        return accelerationWindow.get(accelerationWindow.size() - 1);
    }
    
    public double getFilteredAcceleration() {
        return kalmanFilter.getEstimate();
    }
    
    public List<Double> getAccelerationHistory() {
        return new ArrayList<>(accelerationWindow);
    }
}
