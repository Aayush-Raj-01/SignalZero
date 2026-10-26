package nisargpatel.deadreckoning.activity;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.Locale;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.extra.ExtraFunctions;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.sensor.EnhancedStepCounter;
import nisargpatel.deadreckoning.stepcounting.DynamicStepCounter;
import nisargpatel.deadreckoning.stepcounting.StaticStepCounter;

public class StepsFragment extends Fragment implements SensorEventListener {

    private static final double DEFAULT_STRIDE_LENGTH = 0.75;

    private EnhancedStepCounter enhancedStepCounter;
    private StepCounterPreferences stepPrefs;

    private TextView textSteps;
    private TextView textDistance;
    private TextView textModeUsed;
    private TextView textDynamicCounter;
    private TextView textAndroidCounter;
    private TextView textStaticCounter;
    private TextView textInstantAcc;
    private ProgressBar progressAcc;

    private SensorManager sensorManager;
    private Sensor sensorLinearAcceleration;
    private Sensor sensorAccelerometer;
    private Sensor sensorStepDetector;
    private boolean usingAccelerometerFallback = false;
    private final float[] fallbackGravity = new float[3];
    private boolean fallbackGravityInitialized = false;
    private StepCounterPreferences.StepMode activeStepMode = StepCounterPreferences.StepMode.DYNAMIC;
    private double strideLength = DEFAULT_STRIDE_LENGTH;
    private long stepModeStartedElapsedMs = 0;
    private long lastHardwareStepElapsedMs = 0;

    private StaticStepCounter[] staticStepCounters;
    private DynamicStepCounter[] dynamicStepCounters;

    private int androidStepCount = 0;
    private int currentModeSteps = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_steps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        stepPrefs = new StepCounterPreferences(requireContext());

        initViews(view);
        initSensors();
        initStepcounters();
        updateModeDisplay();
    }

    private void initViews(View view) {
        textSteps = view.findViewById(R.id.textSteps);
        textDistance = view.findViewById(R.id.textDistance);
        textModeUsed = view.findViewById(R.id.textModeUsed);
        textDynamicCounter = view.findViewById(R.id.textDynamicCounter);
        textAndroidCounter = view.findViewById(R.id.textAndroidCounter);
        textStaticCounter = view.findViewById(R.id.textStaticCounter);
        textInstantAcc = view.findViewById(R.id.textInstantAcc);
        progressAcc = view.findViewById(R.id.progressAcc);
    }

    private void updateModeDisplay() {
        StepCounterPreferences.StepMode mode = activeStepMode;
        String modeText;
        
        switch (mode) {
            case DYNAMIC:
                modeText = getString(R.string.dynamic_mode);
                break;
            case ANDROID:
                modeText = getString(R.string.android_mode);
                break;
            case STATIC:
                modeText = getString(R.string.static_mode);
                break;
            default:
                modeText = getString(R.string.dynamic_mode);
        }
        
        if (textModeUsed != null) {
            textModeUsed.setText("Mode: " + modeText);
        }
    }

    private void initSensors() {
        sensorManager = (SensorManager) requireContext().getSystemService(requireContext().SENSOR_SERVICE);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorStepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        strideLength = requireContext().getSharedPreferences("CalibrationPrefs", android.content.Context.MODE_PRIVATE)
                .getFloat("stride_length", (float) DEFAULT_STRIDE_LENGTH);
        if (strideLength < 0.25 || strideLength > 1.5) strideLength = DEFAULT_STRIDE_LENGTH;
    }

    private void initStepcounters() {
        enhancedStepCounter = new EnhancedStepCounter();
        enhancedStepCounter.setStrideLength(strideLength);

        staticStepCounters = new StaticStepCounter[5];
        staticStepCounters[0] = new StaticStepCounter(2, 1.9);
        staticStepCounters[1] = new StaticStepCounter(3, 2.9);
        staticStepCounters[2] = new StaticStepCounter(4, 3.9);
        staticStepCounters[3] = new StaticStepCounter(5, 4.9);
        staticStepCounters[4] = new StaticStepCounter(6, 5.9);

        dynamicStepCounters = new DynamicStepCounter[5];
        dynamicStepCounters[0] = new DynamicStepCounter(0.875);
        dynamicStepCounters[1] = new DynamicStepCounter(0.80);
        dynamicStepCounters[2] = new DynamicStepCounter(0.85);
        dynamicStepCounters[3] = new DynamicStepCounter(0.90);
        dynamicStepCounters[4] = new DynamicStepCounter(0.95);
    }

    @Override
    public void onResume() {
        super.onResume();
        stepPrefs = new StepCounterPreferences(requireContext());
        activeStepMode = stepPrefs.getStepMode();
        boolean hasActivityPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        if (activeStepMode == StepCounterPreferences.StepMode.ANDROID
                && (sensorStepDetector == null || !hasActivityPermission
                || !sensorManager.registerListener(this, sensorStepDetector, SensorManager.SENSOR_DELAY_FASTEST))) {
            activeStepMode = StepCounterPreferences.StepMode.DYNAMIC;
        }
        stepModeStartedElapsedMs = SystemClock.elapsedRealtime();
        lastHardwareStepElapsedMs = 0;
        updateModeDisplay();

        usingAccelerometerFallback = false;
        boolean linearSensorRegistered = sensorLinearAcceleration != null
                && sensorManager.registerListener(this, sensorLinearAcceleration, SensorManager.SENSOR_DELAY_GAME);
        if (!linearSensorRegistered && sensorAccelerometer != null) {
            usingAccelerometerFallback = sensorManager.registerListener(this, sensorAccelerometer,
                    SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            if (activeStepMode == StepCounterPreferences.StepMode.ANDROID && event.values[0] >= 1.0f) {
                androidStepCount++;
                lastHardwareStepElapsedMs = SystemClock.elapsedRealtime();
                currentModeSteps = androidStepCount;
                updateCounts();
            }
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            processLinearAcceleration(event.values, event.timestamp);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER
                && usingAccelerometerFallback) {
            float[] values = event.values.clone();
            float[] linearAcceleration = new float[3];
            final float gravitySmoothing = 0.8f;
            for (int axis = 0; axis < 3; axis++) {
                if (!fallbackGravityInitialized) fallbackGravity[axis] = values[axis];
                else fallbackGravity[axis] = gravitySmoothing * fallbackGravity[axis]
                        + (1f - gravitySmoothing) * values[axis];
                linearAcceleration[axis] = values[axis] - fallbackGravity[axis];
            }
            fallbackGravityInitialized = true;
            processLinearAcceleration(linearAcceleration, event.timestamp);
        }
    }

    private void processLinearAcceleration(float[] values, long timestamp) {
        if (!isAdded() || getActivity() == null) return;
        double norm = ExtraFunctions.calcNorm(values[0], values[1], values[2]);

        getActivity().runOnUiThread(() -> {
            if (!isAdded() || textInstantAcc == null || progressAcc == null) return;
            textInstantAcc.setText(String.format(Locale.US, "%.2f", norm));
            progressAcc.setProgress((int) Math.min(norm * 2, 20));
        });

        boolean stepDetected = enhancedStepCounter.detectStep(values, timestamp);

        for (StaticStepCounter counter : staticStepCounters) {
            counter.findStep(norm);
        }

        for (DynamicStepCounter counter : dynamicStepCounters) {
            counter.findStep(norm);
        }

        switch (activeStepMode) {
            case DYNAMIC:
                currentModeSteps = enhancedStepCounter.getStepCount();
                break;
            case ANDROID:
                currentModeSteps = androidStepCount;
                break;
            case STATIC:
                currentModeSteps = staticStepCounters[0].getStepCount();
                break;
        }

        fallbackIfHardwareSensorIsSilent();
        updateCounts();
    }

    private void fallbackIfHardwareSensorIsSilent() {
        if (activeStepMode != StepCounterPreferences.StepMode.ANDROID
                || enhancedStepCounter.getStepCount() == 0) return;
        long now = SystemClock.elapsedRealtime();
        long lastHardwareEvent = lastHardwareStepElapsedMs == 0
                ? stepModeStartedElapsedMs : lastHardwareStepElapsedMs;
        if (now - lastHardwareEvent < 5000L) return;

        activeStepMode = StepCounterPreferences.StepMode.DYNAMIC;
        currentModeSteps = enhancedStepCounter.getStepCount();
        updateModeDisplay();
    }

    private void updateCounts() {
        if (!isAdded() || getActivity() == null) return;
        final int displaySteps = currentModeSteps;
        final int androidSteps = androidStepCount;
        final int dynamicSteps = enhancedStepCounter.getStepCount();
        final int staticSteps = staticStepCounters[0].getStepCount();
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || textSteps == null) return;
            textSteps.setText(String.valueOf(displaySteps));
            textDistance.setText(String.format(Locale.US, "%.2f m", displaySteps * strideLength));
            textAndroidCounter.setText(String.valueOf(androidSteps));
            textDynamicCounter.setText(String.valueOf(dynamicSteps));
            textStaticCounter.setText(String.valueOf(staticSteps));
        });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
