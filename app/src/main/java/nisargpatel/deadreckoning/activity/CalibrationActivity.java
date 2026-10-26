package nisargpatel.deadreckoning.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.extra.ExtraFunctions;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.sensor.EnhancedStepCounter;

public class CalibrationActivity extends AppCompatActivity implements SensorEventListener {

    public static final String EXTRA_FIRST_RUN_SETUP = "first_run_setup";
    private static final double MIN_CALIBRATION_DISTANCE_METERS = 20.0;
    private static final int MIN_CALIBRATION_STEPS = 15;

    private static final String PREFS_NAME = "CalibrationPrefs";
    private static final String PREF_STRIDE_LENGTH = "stride_length";
    private static final String PREF_CALIBRATED = "is_calibrated";

    private SharedPreferences prefs;
    private StepCounterPreferences stepPrefs;
    private SensorManager sensorManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private RadioGroup radioGroupStepMode;
    private MaterialRadioButton radioDynamic, radioAndroid, radioStatic;
    private Button buttonSaveStepMode;

    private Sensor sensorLinearAcceleration;
    private Sensor sensorAccelerometer;
    private Sensor sensorMagneticField;
    private Sensor sensorGravity;
    private boolean usingAccelerometerFallback = false;
    private final float[] fallbackGravity = new float[3];
    private boolean fallbackGravityInitialized = false;

    private TextView textCurrentSteps;
    private TextView textMeasuredDistance;
    private TextView textCalculatedStride;
    private TextView textGPSStatus;
    private TextView textHeadingValue;
    private TextView textAccuracyValue;
    private TextView textCalibrationStatus;
    private TextView textFirstRunInstructions;
    private CircularProgressIndicator progressIndicator;

    private TextInputEditText inputKnownDistance;
    private TextInputEditText inputKnownSteps;

    private Button buttonStartCalibration;
    private Button buttonSaveCalibration;
    private Button buttonResetCalibration;

    private int calibrationStepCount = 0;
    private double measuredDistance = 0;
    private double calculatedStrideLength = 0.75;
    private double startLatitude = 0;
    private double startLongitude = 0;
    private double lastLatitude = 0;
    private double lastLongitude = 0;
    private boolean isCalibrating = false;
    private boolean isFirstRunSetup = false;
    private long calibrationStartedElapsedMs = 0;
    private long lastHardwareStepElapsedMs = 0;
    private Location calibrationStartFix;
    private Location calibrationLastFix;
    private final AtomicBoolean isStepDetected = new AtomicBoolean(false);
    private double lastAccMagnitude = 0;
    
    private EnhancedStepCounter stepCounter;
    private boolean useAndroidSteps = false;
    private int androidStepCount = 0;

    private float[] gravityValues = null;
    private float[] magValues = null;
    private double currentHeading = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        isFirstRunSetup = getIntent().getBooleanExtra(EXTRA_FIRST_RUN_SETUP, false);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        stepPrefs = new StepCounterPreferences(this);

        initViews();
        setupToolbar();
        initSensors();
        loadSavedCalibration();
        loadStepModeSelection();
    }

    private void initViews() {
        textCurrentSteps = findViewById(R.id.textCurrentSteps);
        textMeasuredDistance = findViewById(R.id.textMeasuredDistance);
        textCalculatedStride = findViewById(R.id.textCalculatedStride);
        textGPSStatus = findViewById(R.id.textGPSStatus);
        textHeadingValue = findViewById(R.id.textHeadingValue);
        textAccuracyValue = findViewById(R.id.textAccuracyValue);
        textCalibrationStatus = findViewById(R.id.textCalibrationStatus);
        textFirstRunInstructions = findViewById(R.id.textFirstRunInstructions);
        progressIndicator = findViewById(R.id.progressIndicator);

        inputKnownDistance = findViewById(R.id.inputKnownDistance);
        inputKnownSteps = findViewById(R.id.inputKnownSteps);

        buttonStartCalibration = findViewById(R.id.buttonStartCalibration);
        buttonSaveCalibration = findViewById(R.id.buttonSaveCalibration);
        buttonResetCalibration = findViewById(R.id.buttonResetCalibration);

        if (isFirstRunSetup) {
            textFirstRunInstructions.setVisibility(android.view.View.VISIBLE);
            buttonStartCalibration.setText("Start 30 m walk");
            findViewById(R.id.cardFirstRunInstructions).setVisibility(android.view.View.VISIBLE);
        }

        radioGroupStepMode = findViewById(R.id.radioGroupStepMode);
        radioDynamic = findViewById(R.id.radioDynamic);
        radioAndroid = findViewById(R.id.radioAndroid);
        radioStatic = findViewById(R.id.radioStatic);
        buttonSaveStepMode = findViewById(R.id.buttonSaveStepMode);

        buttonStartCalibration.setOnClickListener(v -> toggleCalibration());
        buttonSaveCalibration.setOnClickListener(v -> saveCalibration());
        buttonResetCalibration.setOnClickListener(v -> resetCalibration());
        buttonSaveStepMode.setOnClickListener(v -> saveStepMode());
        
        radioGroupStepMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioDynamic) {
                showStepModeInfo("Dynamic: Uses acceleration + moving average. Best for walking.");
            } else if (checkedId == R.id.radioAndroid) {
                showStepModeInfo("Android: Uses built-in step detector. Most accurate but needs hardware support.");
            } else if (checkedId == R.id.radioStatic) {
                showStepModeInfo("Static: Uses simple thresholds. Fast but less accurate.");
            }
        });
    }
    
    private void showStepModeInfo(String message) {
        textCalibrationStatus.setText(message);
        textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (isFirstRunSetup) toolbar.setTitle("First-time setup");
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorMagneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        stepCounter = new EnhancedStepCounter();
    }

    private void loadSavedCalibration() {
        double savedStride = prefs.getFloat(PREF_STRIDE_LENGTH, 0.75f);
        boolean wasCalibrated = prefs.getBoolean(PREF_CALIBRATED, false);

        calculatedStrideLength = savedStride;
        textCalculatedStride.setText(String.format(Locale.US, "%.2f m", savedStride));

        if (wasCalibrated) {
            textCalibrationStatus.setText("Calibrated");
            textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
            buttonSaveCalibration.setEnabled(false);
        }
    }

    private void loadStepModeSelection() {
        StepCounterPreferences.StepMode mode = stepPrefs.getStepMode();
        switch (mode) {
            case DYNAMIC: radioDynamic.setChecked(true); break;
            case ANDROID: radioAndroid.setChecked(true); break;
            case STATIC: radioStatic.setChecked(true); break;
        }
    }

    private void saveStepMode() {
        StepCounterPreferences.StepMode selectedMode;
        String modeName;
        if (radioDynamic.isChecked()) {
            selectedMode = StepCounterPreferences.StepMode.DYNAMIC;
            modeName = "Dynamic";
        } else if (radioAndroid.isChecked()) {
            selectedMode = StepCounterPreferences.StepMode.ANDROID;
            modeName = "Android";
        } else {
            selectedMode = StepCounterPreferences.StepMode.STATIC;
            modeName = "Static";
        }
        
        stepPrefs.setStepMode(selectedMode);
        Toast.makeText(this, "Step mode: " + modeName + " saved!", Toast.LENGTH_SHORT).show();
        
        new AlertDialog.Builder(this)
            .setTitle("Step Mode Changed")
            .setMessage("The step counting mode has been changed to " + modeName + ".\n\n" +
                (selectedMode == StepCounterPreferences.StepMode.DYNAMIC ? 
                    "Uses acceleration + moving average. Best for walking." :
                    selectedMode == StepCounterPreferences.StepMode.ANDROID ?
                    "Uses built-in step detector. Most accurate." :
                    "Uses simple thresholds. Fast but less accurate."))
            .setPositiveButton("OK", null)
            .show();
    }

    private void toggleCalibration() {
        if (isCalibrating) {
            stopCalibration();
        } else {
            startCalibration();
        }
    }

    @SuppressLint("MissingPermission")
    private void startCalibration() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission is required for calibration", Toast.LENGTH_LONG).show();
            return;
        }

        calibrationStepCount = 0;
        measuredDistance = 0;
        startLatitude = 0;
        startLongitude = 0;
        lastLatitude = 0;
        lastLongitude = 0;
        calibrationStartFix = null;
        calibrationLastFix = null;
        buttonSaveCalibration.setEnabled(false);
        isCalibrating = true;

        buttonStartCalibration.setText("Stop");
        buttonStartCalibration.setBackgroundColor(ContextCompat.getColor(this, R.color.colorError));
        progressIndicator.setVisibility(CircularProgressIndicator.VISIBLE);

        textCurrentSteps.setText("0");
        textMeasuredDistance.setText("0.00 m");
        textCalculatedStride.setText("0.75 m");
        textGPSStatus.setText("Getting GPS...");
        
        if (stepCounter != null) {
            stepCounter.reset();
        }
        
        Sensor stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        boolean hasActivityPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean requestAndroidSteps = stepDetector != null && hasActivityPermission
                && stepPrefs.getStepMode() == StepCounterPreferences.StepMode.ANDROID;
        useAndroidSteps = requestAndroidSteps && sensorManager.registerListener(this, stepDetector,
                SensorManager.SENSOR_DELAY_FASTEST);

        usingAccelerometerFallback = false;
        boolean linearSensorRegistered = sensorLinearAcceleration != null
                && sensorManager.registerListener(this, sensorLinearAcceleration, SensorManager.SENSOR_DELAY_GAME);
        if (!linearSensorRegistered && sensorAccelerometer != null) {
            usingAccelerometerFallback = sensorManager.registerListener(this, sensorAccelerometer,
                    SensorManager.SENSOR_DELAY_GAME);
        }
        if (!useAndroidSteps && !linearSensorRegistered && !usingAccelerometerFallback) {
            isCalibrating = false;
            sensorManager.unregisterListener(this);
            buttonStartCalibration.setText(isFirstRunSetup ? "Start 30 m walk" : getString(R.string.start));
            progressIndicator.setVisibility(CircularProgressIndicator.GONE);
            textGPSStatus.setText("No motion sensor available for step calibration");
            Toast.makeText(this, "Step sensor unavailable; calibration cannot start", Toast.LENGTH_LONG).show();
            return;
        }

        if (requestAndroidSteps && !useAndroidSteps) {
            Toast.makeText(this, "Using motion-based step detection for calibration", Toast.LENGTH_LONG).show();
        }
        if (sensorMagneticField != null) {
            sensorManager.registerListener(this, sensorMagneticField, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensorGravity != null) {
            sensorManager.registerListener(this, sensorGravity, SensorManager.SENSOR_DELAY_GAME);
        }
        calibrationStartedElapsedMs = SystemClock.elapsedRealtime();
        lastHardwareStepElapsedMs = 0;
        fallbackGravityInitialized = false;
        stepCounter.reset();

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    handleGPSUpdate(location);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
        
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && isCalibrating) {
                handleGPSUpdate(location);
            }
        });
        
        Toast.makeText(this, "Walk to calibrate - GPS acquiring...", Toast.LENGTH_SHORT).show();
    }

    private void stopCalibration() {
        isCalibrating = false;
        sensorManager.unregisterListener(this);
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        buttonStartCalibration.setText("Start");
        buttonStartCalibration.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        progressIndicator.setVisibility(CircularProgressIndicator.GONE);

        if (isCalibrationResultUsable()) {
            calculatedStrideLength = measuredDistance / calibrationStepCount;
            textCalculatedStride.setText(String.format(Locale.US, "%.2f m", calculatedStrideLength));
            buttonSaveCalibration.setEnabled(true);
            textCalibrationStatus.setText(String.format(Locale.US,
                    "Calibration ready: %.1f m over %d steps", measuredDistance, calibrationStepCount));
            textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
        } else {
            buttonSaveCalibration.setEnabled(false);
            textCalibrationStatus.setText(String.format(Locale.US,
                    "Walk straight at least 20 m and 15 steps with GPS accuracy under 15 m. Current: %.1f m, %d steps",
                    measuredDistance, calibrationStepCount));
            textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorWarning));
        }
    }

    private boolean isCalibrationResultUsable() {
        if (calibrationStepCount < MIN_CALIBRATION_STEPS) return false;
        double candidateStride = measuredDistance / calibrationStepCount;
        return calibrationStartFix != null
                && measuredDistance >= MIN_CALIBRATION_DISTANCE_METERS
                && candidateStride >= 0.25 && candidateStride <= 1.5;
    }

    private void handleGPSUpdate(Location location) {
        if (!isCalibrating) return;

        if (!location.hasAccuracy() || location.getAccuracy() > 15f
                || location.getTime() <= 0
                || Math.abs(System.currentTimeMillis() - location.getTime()) > 5000L) {
            textGPSStatus.setText("Waiting for a good GPS fix (accuracy under 15 m)");
            textGPSStatus.setTextColor(ContextCompat.getColor(this, R.color.colorWarning));
            return;
        }

        if (calibrationLastFix != null && location.getTime() <= calibrationLastFix.getTime()) return;

        double accuracy = location.getAccuracy();
        textAccuracyValue.setText(String.format(Locale.US, "%.1f m", accuracy));

        if (calibrationStartFix == null) {
            calibrationStartFix = new Location(location);
            textGPSStatus.setText("GPS ready — walk straight for about 30 m");
        }
        calibrationLastFix = new Location(location);
        measuredDistance = calibrationStartFix.distanceTo(calibrationLastFix);
        textMeasuredDistance.setText(String.format(Locale.US, "%.1f m", measuredDistance));

        if (calibrationStepCount > 0 && measuredDistance >= 1.0) {
            double currentStride = measuredDistance / calibrationStepCount;
            textCalculatedStride.setText(String.format(Locale.US, "%.2f m", currentStride));
        }
    }

    private void saveCalibration() {
        String knownDistanceStr = inputKnownDistance.getText().toString();
        String knownStepsStr = inputKnownSteps.getText().toString();

        if (isFirstRunSetup && !isCalibrationResultUsable()) {
            Toast.makeText(this, "Complete the GPS walk before saving setup", Toast.LENGTH_LONG).show();
            return;
        }

        if (!knownDistanceStr.isEmpty() && !knownStepsStr.isEmpty() && !isFirstRunSetup) {
            try {
                double knownDistance = Double.parseDouble(knownDistanceStr);
                int knownSteps = Integer.parseInt(knownStepsStr);
                if (knownSteps > 0) {
                    calculatedStrideLength = knownDistance / knownSteps;
                }
            } catch (NumberFormatException ignored) {}
        }

        if (calculatedStrideLength < 0.25 || calculatedStrideLength > 1.5) {
            Toast.makeText(this, "Calculated stride looks invalid. Repeat the straight GPS walk.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        prefs.edit()
                .putFloat(PREF_STRIDE_LENGTH, (float) calculatedStrideLength)
                .putBoolean(PREF_CALIBRATED, true)
                .putBoolean("first_run_setup_complete", true)
                .apply();

        textCalibrationStatus.setText("Calibrated");
        textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
        textCalculatedStride.setText(String.format(Locale.US, "%.2f m", calculatedStrideLength));
        buttonSaveCalibration.setEnabled(false);

        Toast.makeText(this, "Calibration saved!", Toast.LENGTH_SHORT).show();
        if (isFirstRunSetup) {
            startActivity(new android.content.Intent(this, MainContainerActivity.class));
            finish();
        }
    }

    private void resetCalibration() {
        new AlertDialog.Builder(this)
                .setTitle("Reset")
                .setMessage("Do you want to reset the calibration?")
                .setPositiveButton("Yes", (d, w) -> {
                    prefs.edit()
                            .putFloat(PREF_STRIDE_LENGTH, 0.75f)
                            .putBoolean(PREF_CALIBRATED, false)
                            .apply();

                    calculatedStrideLength = 0.75;
                    textCalculatedStride.setText("0.75 m");
                    textCalibrationStatus.setText("Not calibrated");
                    textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                    buttonSaveCalibration.setEnabled(false);
                    Toast.makeText(this, "Calibration reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_LINEAR_ACCELERATION:
                if (isCalibrating) {
                    processStepDetection(event.values, event.timestamp);
                }
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                if (isCalibrating && useAndroidSteps) {
                    calibrationStepCount++;
                    lastHardwareStepElapsedMs = SystemClock.elapsedRealtime();
                    textCurrentSteps.setText(String.valueOf(calibrationStepCount));
                    if (measuredDistance > 0 && calibrationStepCount > 0) {
                        double currentStride = measuredDistance / calibrationStepCount;
                        textCalculatedStride.setText(String.format(Locale.US, "%.2f m", currentStride));
                    }
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                if (isCalibrating && usingAccelerometerFallback && !useAndroidSteps) {
                    float[] linearAcceleration = new float[3];
                    final float gravitySmoothing = 0.8f;
                    for (int axis = 0; axis < 3; axis++) {
                        if (!fallbackGravityInitialized) fallbackGravity[axis] = event.values[axis];
                        else fallbackGravity[axis] = gravitySmoothing * fallbackGravity[axis]
                                + (1f - gravitySmoothing) * event.values[axis];
                        linearAcceleration[axis] = event.values[axis] - fallbackGravity[axis];
                    }
                    fallbackGravityInitialized = true;
                    gravityValues = fallbackGravity.clone();
                    updateHeading();
                    processStepDetection(linearAcceleration, event.timestamp);
                }
                break;
            case Sensor.TYPE_GRAVITY:
                gravityValues = event.values.clone();
                updateHeading();
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                magValues = event.values.clone();
                updateHeading();
                break;
        }
    }

    private void processStepDetection(float[] values, long timestamp) {
        boolean dynamicStepDetected = stepCounter.detectStep(values, timestamp);
        if (useAndroidSteps && calibrationStepCount == 0
                && stepCounter.getStepCount() > 0
                && SystemClock.elapsedRealtime() - calibrationStartedElapsedMs >= 5000L) {
            useAndroidSteps = false;
            calibrationStepCount = stepCounter.getStepCount();
            textGPSStatus.setText("Hardware step sensor was quiet; using motion-based steps");
            textCurrentSteps.setText(String.valueOf(calibrationStepCount));
        } else if (!useAndroidSteps && dynamicStepDetected) {
            calibrationStepCount++;
            textCurrentSteps.setText(String.valueOf(calibrationStepCount));
            
            if (measuredDistance > 0 && calibrationStepCount > 0) {
                double currentStride = measuredDistance / calibrationStepCount;
                textCalculatedStride.setText(String.format(Locale.US, "%.2f m", currentStride));
            }
        }
    }

    private void updateHeading() {
        if (gravityValues != null && magValues != null) {
            float[] rotationMatrix = new float[9];
            float[] orientationAngles = new float[3];
            if (SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, magValues)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                currentHeading = Math.toDegrees(orientationAngles[0]);
                if (currentHeading < 0) currentHeading += 360;
                textHeadingValue.setText(String.format(Locale.US, "%.1f°", currentHeading));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onPause() {
        super.onPause();
        if (isCalibrating) {
            stopCalibration();
        }
        sensorManager.unregisterListener(this);
    }
}
