package nisargpatel.deadreckoning.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.osmdroid.api.IMapController;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.cachemanager.CacheManager.CacheManagerCallback;
import org.osmdroid.tileprovider.cachemanager.CacheManager.CacheManagerTask;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.DeadReckoningApp;
import nisargpatel.deadreckoning.model.Marker;
import nisargpatel.deadreckoning.preferences.TurnModePreferences;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.sensor.DeadReckoningEngine;
import nisargpatel.deadreckoning.sensor.PreciseHeadingEstimator;
import nisargpatel.deadreckoning.storage.MarkerStorage;
import nisargpatel.deadreckoning.storage.TripStorage;
import nisargpatel.deadreckoning.view.DegreeDialView;

public class MapFragment extends Fragment implements SensorEventListener {

    private static final long GPS_STALE_TIMEOUT_MS = 3000L;
    private static final double LOOP_CLOSURE_RADIUS_METERS = 5.0;
    private static final int LOOP_CLOSURE_REQUIRED_OBSERVATIONS = 3;

    private MapView mapView;
    private MyLocationNewOverlay locationOverlay;
    private org.osmdroid.views.overlay.Marker estimatedPositionMarker;
    private org.osmdroid.views.overlay.Marker gpsLossMarker;
    private Polyline pathOverlay;
    
    private FusedLocationProviderClient fusedLocationClient;
    private LocationManager locationManager;
    private LocationCallback locationCallback;
    private LocationListener gpsProviderListener;
    private LocationRequest locationRequest;

    private SensorManager sensorManager;
    private Sensor sensorGravity;
    private Sensor sensorMagnetic;
    private Sensor sensorGyroscope;
    private Sensor sensorRotationVector;
    private Sensor sensorLinearAcceleration;
    private Sensor sensorAccelerometer;
    private Sensor sensorStepDetector;
    private boolean usingAccelerometerFallback = false;
    private final float[] fallbackGravity = new float[3];
    private boolean fallbackGravityInitialized = false;
    private StepCounterPreferences.StepMode activeStepMode = StepCounterPreferences.StepMode.DYNAMIC;

    private DeadReckoningEngine deadReckoningEngine;
    private PreciseHeadingEstimator headingEstimator;
    private TripStorage tripStorage;
    private MarkerStorage markerStorage;
    private TurnModePreferences turnModePrefs;
    private StepCounterPreferences stepPrefs;

    private TextView textSteps;
    private TextView textDistance;
    private TextView textHeading;
    private TextView textGPSAccuracy;
    private TextView textGPSStatus;
    private TextView textTurnIndicator;
    private MaterialButton buttonModeToggle;
    private MaterialButton buttonGPSCalibrate;
    private MaterialCardView cardTurn;
    private LinearLayout manualTurnControls;

    private FloatingActionButton buttonTurnLeft;
    private FloatingActionButton buttonTurnRight;
    private FloatingActionButton buttonTurnAround;
    private FloatingActionButton buttonOpenDial;
    private FloatingActionButton fabCenterGPS;
    private FloatingActionButton fabSaveOfflineArea;
    private FloatingActionButton fabAddMarker;
    private FloatingActionButton fabClearAll;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton buttonStartStop;
    private MaterialButton buttonPause;
    private MaterialButton buttonNoGPS;
    private MaterialButton buttonConfirmDial;
    private ImageView imageDirectionArrow;
    private MaterialCardView cardDirection;
    private FrameLayout dialOverlayContainer;
    private DegreeDialView degreeDialView;

    private List<GeoPoint> pathPoints = new ArrayList<>();
    private List<GeoPoint> noGPSPathPoints = new ArrayList<>();
    private List<GeoPoint> recentGPSPoints = new ArrayList<>();
    private List<Marker> mapMarkers = new ArrayList<>();
    private List<org.osmdroid.views.overlay.Marker> markerOverlays = new ArrayList<>();
    private GeoPoint currentPosition = null;
    private GeoPoint startPosition = null;
    private GeoPoint lastGPSPoint = null;

    private boolean isTracking = false;
    private boolean isPaused = false;
    private boolean isManualMode = false;
    private Location lastKnownGPS = null;
    private Location lastReliableGPSFix = null;
    private Location lastGpsCourseFix = null;
    private double gpsTravelBearing = 0.0;
    private boolean hasGpsTravelBearing = false;
    private int gpsTravelBearingSamples = 0;
    private long gpsCourseUpdatedElapsedMs = 0L;
    private double offlineHeadingOffset = 0.0;
    private boolean hasOfflineHeadingOffset = false;
    private boolean useGPSForTrace = false;
    private boolean forceNoGPSMode = false;
    private boolean autoNoGPSMode = false;
    private long lastGPSMoveTime = 0;
    private long trackingStartedElapsedMs = 0;
    private long lastHardwareStepElapsedMs = 0;
    private GeoPoint lastDeadReckoningPoint = null;
    private GeoPoint noGPSLoopAnchor = null;
    private int loopClosureObservations = 0;
    private double noGPSDistanceSinceAnchor = 0;
    private double lastDeadReckoningDistance = 0;
    private double noGPSFixedHeading = 0;
    private Polyline noGPSPathOverlay;
    private boolean isAddingMarker = false;
    private boolean offlineMapDownloadRunning = false;
    private String selectedEmoji = "📍";
    private GeoPoint pendingMarkerPosition = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        turnModePrefs = new TurnModePreferences(requireContext());
        stepPrefs = new StepCounterPreferences(requireContext());
        markerStorage = new MarkerStorage(requireContext());
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;

        initViews(view);
        initSensors();
        initLocation();
        initMap(view);
        initEngine();
        updateTurnModeUI();
        loadMarkers();
    }

    private void initViews(View view) {
        textSteps = view.findViewById(R.id.textSteps);
        textDistance = view.findViewById(R.id.textDistance);
        textHeading = view.findViewById(R.id.textHeading);
        textGPSAccuracy = view.findViewById(R.id.textGPSAccuracy);
        textGPSStatus = view.findViewById(R.id.textGPSStatus);
        textTurnIndicator = view.findViewById(R.id.textTurnIndicator);
        buttonModeToggle = view.findViewById(R.id.buttonModeToggle);
        buttonGPSCalibrate = view.findViewById(R.id.buttonGPSCalibrate);
        cardTurn = view.findViewById(R.id.cardTurn);
        manualTurnControls = view.findViewById(R.id.manualTurnControls);

        buttonTurnLeft = view.findViewById(R.id.buttonTurnLeft);
        buttonTurnRight = view.findViewById(R.id.buttonTurnRight);
        buttonTurnAround = view.findViewById(R.id.buttonTurnAround);
        fabCenterGPS = view.findViewById(R.id.fabCenterGPS);
        fabSaveOfflineArea = view.findViewById(R.id.fabSaveOfflineArea);
        fabAddMarker = view.findViewById(R.id.fabAddMarker);
        fabClearAll = view.findViewById(R.id.fabClearAll);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPause = view.findViewById(R.id.buttonPause);
        buttonNoGPS = view.findViewById(R.id.buttonNoGPS);
        imageDirectionArrow = view.findViewById(R.id.imageDirectionArrow);
        cardDirection = view.findViewById(R.id.cardDirection);

        buttonOpenDial = view.findViewById(R.id.buttonOpenDial);
        dialOverlayContainer = view.findViewById(R.id.dialOverlayContainer);
        degreeDialView = view.findViewById(R.id.degreeDialView);
        buttonConfirmDial = view.findViewById(R.id.buttonConfirmDial);

        buttonModeToggle.setOnClickListener(v -> toggleTurnMode());
        
        if (buttonNoGPS != null) {
            buttonNoGPS.setOnClickListener(v -> {
                autoNoGPSMode = false;
                toggleNoGPSMode();
            });
        }
        
        buttonGPSCalibrate.setOnClickListener(v -> calibrateGPS());

        buttonOpenDial.setOnClickListener(v -> openDialOverlay());
        
        dialOverlayContainer.setOnClickListener(v -> closeDialOverlay());
        
        degreeDialView.setOnDegreeChangedListener(degree -> {
            textHeading.setText(String.format("%.0f°", degree));
        });
        
        buttonConfirmDial.setOnClickListener(v -> confirmDialSelection());
        
        buttonTurnLeft.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnLeft();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("Left ↰");
            }
        });
        
        buttonTurnRight.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnRight();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("Right ↱");
            }
        });
        
        buttonTurnAround.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnAround();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("U-Turn 🔄");
            }
        });

        fabCenterGPS.setOnClickListener(v -> centerOnGPS());
        fabSaveOfflineArea.setOnClickListener(v -> saveVisibleMapAreaOffline());
        
        fabAddMarker.setOnClickListener(v -> toggleAddMarkerMode());
        
        fabClearAll.setOnClickListener(v -> showClearAllDialog());
        
        if (buttonStartStop != null) {
            buttonStartStop.setOnClickListener(v -> {
                if (isTracking) {
                    stopTracking();
                } else {
                    startTracking();
                }
            });
        }
        
        if (buttonPause != null) {
            buttonPause.setOnClickListener(v -> togglePause());
        }
    }
    
    private void togglePause() {
        if (!isTracking) return;
        
        isPaused = !isPaused;
        
        if (isPaused) {
            buttonPause.setText(R.string.resume);
            buttonPause.setIconResource(R.drawable.ic_play);
            Toast.makeText(requireContext(), "Tracking paused", Toast.LENGTH_SHORT).show();
        } else {
            buttonPause.setText(R.string.pause);
            buttonPause.setIconResource(R.drawable.ic_pause);
            Toast.makeText(requireContext(), "Tracking resumed", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleNoGPSMode() {
        forceNoGPSMode = !forceNoGPSMode;
        useGPSForTrace = !forceNoGPSMode;
        
        if (forceNoGPSMode) {
            if (locationOverlay != null) {
                locationOverlay.disableFollowLocation();
                locationOverlay.disableMyLocation();
            }
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
            manualTurnControls.setVisibility(View.VISIBLE);
            
            Location anchorFix = lastReliableGPSFix != null ? lastReliableGPSFix : lastKnownGPS;
            if (anchorFix != null) {
                currentPosition = new GeoPoint(anchorFix.getLatitude(), anchorFix.getLongitude());
                lastDeadReckoningPoint = currentPosition;
                if (deadReckoningEngine != null && isTracking) {
                    deadReckoningEngine.reanchorAtLocation(anchorFix);
                }
            } else if (currentPosition != null) {
                lastDeadReckoningPoint = currentPosition;
            }
            noGPSLoopAnchor = lastDeadReckoningPoint == null
                    ? null : new GeoPoint(lastDeadReckoningPoint);
            resetLoopClosureEvidence();

            if (gpsLossMarker != null && lastDeadReckoningPoint != null) {
                gpsLossMarker.setPosition(lastDeadReckoningPoint);
                gpsLossMarker.setTitle(autoNoGPSMode ? "GPS lost here" : "Offline estimate started here");
                gpsLossMarker.setSnippet(autoNoGPSMode
                        ? "Last reliable GPS position"
                        : "Position used to start dead reckoning");
                gpsLossMarker.setEnabled(true);
            }
            if (textGPSStatus != null) {
                textGPSStatus.setText(getOfflinePositionStatus());
                textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
            }

            double deviceHeading = deadReckoningEngine != null
                    ? deadReckoningEngine.getHeading() : noGPSFixedHeading;
            boolean recentGpsCourse = !isManualMode
                    && hasGpsTravelBearing && gpsTravelBearingSamples > 0
                    && SystemClock.elapsedRealtime() - gpsCourseUpdatedElapsedMs <= 45_000L;
            hasOfflineHeadingOffset = recentGpsCourse;
            if (recentGpsCourse) {
                // Start from the direction the phone was actually travelling over
                // the ground. Keep the learned offset as the phone turns offline.
                offlineHeadingOffset = signedAngleDifference(gpsTravelBearing, deviceHeading);
                noGPSFixedHeading = normalizeBearing(deviceHeading + offlineHeadingOffset);
            } else {
                offlineHeadingOffset = 0.0;
                noGPSFixedHeading = normalizeBearing(deviceHeading);
            }
            if (deadReckoningEngine != null) {
                deadReckoningEngine.setTravelHeadingOffset(hasOfflineHeadingOffset
                        ? offlineHeadingOffset : 0.0);
            }
            if (textGPSStatus != null) textGPSStatus.setText(getOfflinePositionStatus());
            noGPSDistanceSinceAnchor = 0;
            if (lastDeadReckoningPoint != null && (noGPSPathPoints.isEmpty()
                    || noGPSPathPoints.get(noGPSPathPoints.size() - 1)
                    .distanceToAsDouble(lastDeadReckoningPoint) > 2.0)) {
                noGPSPathPoints.add(lastDeadReckoningPoint);
            }

            if (estimatedPositionMarker != null && lastDeadReckoningPoint != null) {
                estimatedPositionMarker.setPosition(lastDeadReckoningPoint);
                estimatedPositionMarker.setEnabled(true);
                mapView.getController().animateTo(lastDeadReckoningPoint);
                mapView.invalidate();
            }
            
            if (deadReckoningEngine != null) {
                lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                deadReckoningEngine.resetToCompassHeading();
            }
            
            String modeMessage = lastDeadReckoningPoint == null
                    ? "No recent position. Get a GPS fix before going offline."
                    : "GPS unavailable — showing estimated position. Use arrows to turn.";
            Toast.makeText(requireContext(), modeMessage, Toast.LENGTH_SHORT).show();
        } else {
            noGPSDistanceSinceAnchor = 0;
            noGPSLoopAnchor = null;
            resetLoopClosureEvidence();
            hasOfflineHeadingOffset = false;
            if (deadReckoningEngine != null) deadReckoningEngine.setTravelHeadingOffset(0.0);
            if (estimatedPositionMarker != null) estimatedPositionMarker.setEnabled(false);
            if (locationOverlay != null && hasLocationPermission()) {
                locationOverlay.enableMyLocation();
                locationOverlay.enableFollowLocation();
            }
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
            if (!isManualMode) {
                manualTurnControls.setVisibility(View.GONE);
            }
            if (deadReckoningEngine != null) {
                deadReckoningEngine.resetToCompassHeading();
            }
            Toast.makeText(requireContext(), "Mode: GPS (Compass)", Toast.LENGTH_SHORT).show();
        }
    }

    private GeoPoint calculatePoint5MetersBack() {
        if (recentGPSPoints.size() < 2) {
            return null;
        }
        
        GeoPoint lastPoint = recentGPSPoints.get(recentGPSPoints.size() - 1);
        GeoPoint secondLastPoint = recentGPSPoints.get(recentGPSPoints.size() - 2);
        
        double bearing = calculateBearing(secondLastPoint.getLatitude(), secondLastPoint.getLongitude(),
                                          lastPoint.getLatitude(), lastPoint.getLongitude());
        
        double backBearing = (bearing + 180) % 360;
        
        return calculateEstimatedPosition(
            lastPoint.getLatitude(),
            lastPoint.getLongitude(),
            backBearing,
            5.0
        );
    }

    private double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double lon1Rad = Math.toRadians(lon1);
        double lon2Rad = Math.toRadians(lon2);
        
        double dLon = lon2Rad - lon1Rad;
        
        double y = Math.sin(dLon) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - 
                   Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon);
        
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    private void centerOnGPS() {
        if (lastKnownGPS != null) {
            IMapController mapController = mapView.getController();
            mapController.animateTo(new GeoPoint(lastKnownGPS.getLatitude(), lastKnownGPS.getLongitude()));
            mapController.setZoom(19.0);
        } else {
            Toast.makeText(requireContext(), R.string.searching_gps, Toast.LENGTH_SHORT).show();
            startLocationUpdates();
        }
    }

    private void toggleTurnMode() {
        turnModePrefs.toggleTurnMode();
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
        updateTurnModeUI();
        
        String mode = isManualMode ? "Manual" : "Auto";
        Toast.makeText(requireContext(), "Mode: " + mode, Toast.LENGTH_SHORT).show();
    }

    private void calibrateGPS() {
        if (deadReckoningEngine != null) {
            deadReckoningEngine.resetCalibration();
            Toast.makeText(requireContext(), R.string.gps_reset, Toast.LENGTH_LONG).show();
            textGPSStatus.setText("GPS: Calibrating...");
        }
    }

    private void updateTurnModeUI() {
        if (isManualMode) {
            buttonModeToggle.setText("Mode: Manual");
            if (isTracking) {
                manualTurnControls.setVisibility(View.VISIBLE);
            }
        } else {
            buttonModeToggle.setText("Mode: Auto");
        manualTurnControls.setVisibility(View.GONE);

        if (buttonNoGPS != null) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }
        }
    }

    private void showTurnIndicator(String text) {
        textTurnIndicator.setText(text);
        cardTurn.setVisibility(View.VISIBLE);
        
        cardTurn.removeCallbacks(hideTurnRunnable);
        cardTurn.postDelayed(hideTurnRunnable, 2000);
    }

    private final Runnable hideTurnRunnable = new Runnable() {
        @Override
        public void run() {
            cardTurn.setVisibility(View.GONE);
        }
    };

    private void initSensors() {
        sensorManager = (SensorManager) requireContext().getSystemService(requireContext().SENSOR_SERVICE);

        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorStepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
    }

    private void registerSensors() {
        if (sensorGravity != null) {
            sensorManager.registerListener(this, sensorGravity, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensorMagnetic != null) {
            sensorManager.registerListener(this, sensorMagnetic, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensorGyroscope != null) {
            sensorManager.registerListener(this, sensorGyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensorRotationVector != null) {
            sensorManager.registerListener(this, sensorRotationVector, SensorManager.SENSOR_DELAY_GAME);
        }
        usingAccelerometerFallback = false;
        boolean linearSensorRegistered = sensorLinearAcceleration != null
                && sensorManager.registerListener(this, sensorLinearAcceleration, SensorManager.SENSOR_DELAY_GAME);
        if (!linearSensorRegistered && sensorAccelerometer != null) {
            usingAccelerometerFallback = sensorManager.registerListener(this, sensorAccelerometer,
                    SensorManager.SENSOR_DELAY_GAME);
        }
        if (activeStepMode == StepCounterPreferences.StepMode.ANDROID && sensorStepDetector != null) {
            sensorManager.registerListener(this, sensorStepDetector, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    private void initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        locationManager = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);

        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
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

        // GNSS works without mobile data or Wi-Fi. Keep a satellite-only provider
        // active alongside Fused Location so loss of internet alone doesn't trigger
        // dead reckoning.
        gpsProviderListener = location -> handleGPSUpdate(location);
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (hasLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) handleGPSUpdate(location);
            });
            if (locationManager != null && gpsProviderListener != null) {
                try {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                                1000L, 0f, gpsProviderListener, Looper.getMainLooper());
                    }
                } catch (SecurityException | IllegalArgumentException error) {
                    android.util.Log.w("MapFragment", "Satellite location provider unavailable", error);
                }
            }
        }
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (locationManager != null && gpsProviderListener != null) {
            try {
                locationManager.removeUpdates(gpsProviderListener);
            } catch (SecurityException ignored) { }
        }
    }

    private void initMap(View view) {
        mapView = view.findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        
        RotationGestureOverlay rotationGestureOverlay = new RotationGestureOverlay(requireContext(), mapView);
        rotationGestureOverlay.setEnabled(true);
        mapView.getOverlays().add(rotationGestureOverlay);
        
        IMapController mapController = mapView.getController();
        mapController.setZoom(18.0);
        restoreLastKnownLocation();
        if (lastKnownGPS != null) {
            currentPosition = new GeoPoint(lastKnownGPS.getLatitude(), lastKnownGPS.getLongitude());
            mapController.setCenter(currentPosition);
        }
        
        pathOverlay = new Polyline();
        pathOverlay.getOutlinePaint().setColor(ContextCompat.getColor(requireContext(), R.color.colorAccent));
        pathOverlay.getOutlinePaint().setStrokeWidth(12.0f);
        pathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(pathOverlay);
        
        noGPSPathOverlay = new Polyline();
        noGPSPathOverlay.getOutlinePaint().setColor(Color.parseColor("#F44336"));
        noGPSPathOverlay.getOutlinePaint().setStrokeWidth(12.0f);
        noGPSPathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(noGPSPathOverlay);

        estimatedPositionMarker = new org.osmdroid.views.overlay.Marker(mapView);
        estimatedPositionMarker.setTitle("Estimated position");
        estimatedPositionMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM);
        estimatedPositionMarker.setEnabled(false);
        mapView.getOverlays().add(estimatedPositionMarker);

        gpsLossMarker = new org.osmdroid.views.overlay.Marker(mapView);
        gpsLossMarker.setTitle("GPS lost here");
        gpsLossMarker.setSnippet("Last reliable GPS position");
        gpsLossMarker.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_lost));
        gpsLossMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM);
        gpsLossMarker.setEnabled(false);
        mapView.getOverlays().add(gpsLossMarker);

        MapEventsReceiver mReceive = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (isAddingMarker) {
                    showMarkerDialog(p);
                    return true;
                }
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };

        mapView.getOverlays().add(new MapEventsOverlay(mReceive));

        enableLocationComponent();
    }

    @SuppressLint("MissingPermission")
    private void enableLocationComponent() {
        if (hasLocationPermission()) {
            locationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(requireContext()), mapView);
            locationOverlay.enableMyLocation();
            locationOverlay.enableFollowLocation();
            mapView.getOverlays().add(locationOverlay);
        }
    }

    private void updatePathOnMap() {
        if (mapView == null) return;
        if (!pathPoints.isEmpty()) {
            pathOverlay.setPoints(pathPoints);
        }
        if (!noGPSPathPoints.isEmpty()) {
            noGPSPathOverlay.setPoints(noGPSPathPoints);
        }
        mapView.invalidate();
    }

    private void initEngine() {
        deadReckoningEngine = new DeadReckoningEngine();
        headingEstimator = new PreciseHeadingEstimator();
        tripStorage = new TripStorage(requireContext());
    }

    public void startTracking() {
        if (isTracking) return;
        if (!hasLocationPermission()) {
            Toast.makeText(requireContext(), "GPS Permission required", Toast.LENGTH_LONG).show();
            return;
        }
        if (sensorLinearAcceleration == null && sensorAccelerometer == null) {
            Toast.makeText(requireContext(), "This device has no motion sensor for step tracking", Toast.LENGTH_LONG).show();
            return;
        }

        isTracking = true;
        isPaused = false;
        trackingStartedElapsedMs = SystemClock.elapsedRealtime();
        lastHardwareStepElapsedMs = 0;
        
        StepCounterPreferences.StepMode stepMode = stepPrefs.getStepMode();
        boolean hasStepPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        if (stepMode == StepCounterPreferences.StepMode.ANDROID
                && (sensorStepDetector == null || !hasStepPermission)) {
            stepMode = StepCounterPreferences.StepMode.DYNAMIC;
            Toast.makeText(requireContext(), "Hardware step sensor unavailable or not permitted; using motion detection",
                    Toast.LENGTH_LONG).show();
        }
        if (stepMode == StepCounterPreferences.StepMode.ANDROID && sensorStepDetector != null) {
            boolean registered = sensorManager.registerListener(this, sensorStepDetector,
                    SensorManager.SENSOR_DELAY_FASTEST);
            if (!registered) {
                stepMode = StepCounterPreferences.StepMode.DYNAMIC;
                Toast.makeText(requireContext(), "Hardware step sensor did not start; using motion detection",
                        Toast.LENGTH_LONG).show();
            }
        }
        activeStepMode = stepMode;
        deadReckoningEngine.setStepMode(stepMode);
        
        deadReckoningEngine.start();
        fallbackGravityInitialized = false;
        float savedStrideLength = requireContext().getSharedPreferences("CalibrationPrefs", Context.MODE_PRIVATE)
                .getFloat("stride_length", 0.75f);
        if (savedStrideLength >= 0.2f && savedStrideLength <= 2.0f) {
            deadReckoningEngine.setStrideLength(savedStrideLength);
        }
        pathPoints.clear();
        noGPSPathPoints.clear();
        startPosition = null;
        lastGPSPoint = null;
        lastDeadReckoningPoint = null;
        noGPSLoopAnchor = null;
        resetLoopClosureEvidence();
        lastGpsCourseFix = null;
        hasGpsTravelBearing = false;
        gpsTravelBearingSamples = 0;
        gpsCourseUpdatedElapsedMs = 0L;
        hasOfflineHeadingOffset = false;
        deadReckoningEngine.setTravelHeadingOffset(0.0);
        noGPSDistanceSinceAnchor = 0;
        lastDeadReckoningDistance = 0;
        noGPSFixedHeading = 0;

        if (lastKnownGPS != null) {
            deadReckoningEngine.setInitialLocation(lastKnownGPS);
            currentPosition = new GeoPoint(lastKnownGPS.getLatitude(), lastKnownGPS.getLongitude());
            startPosition = currentPosition;
            lastDeadReckoningPoint = currentPosition;
            mapView.getController().animateTo(currentPosition);
        }
        
        forceNoGPSMode = false;
        autoNoGPSMode = false;
        if (estimatedPositionMarker != null) estimatedPositionMarker.setEnabled(false);
        if (gpsLossMarker != null) gpsLossMarker.setEnabled(false);
        if (locationOverlay != null && hasLocationPermission()) {
            locationOverlay.enableMyLocation();
            locationOverlay.enableFollowLocation();
        }
        if (buttonNoGPS != null) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }

        if (isManualMode || forceNoGPSMode) {
            manualTurnControls.setVisibility(View.VISIBLE);
        }
        
        if (buttonPause != null) {
            buttonPause.setVisibility(View.VISIBLE);
            buttonPause.setText(R.string.pause);
            buttonPause.setIconResource(R.drawable.ic_pause);
        }
        if (buttonStartStop != null) {
            buttonStartStop.setText(R.string.stop);
            buttonStartStop.setIconResource(R.drawable.ic_stop);
        }
        if (cardDirection != null) {
            cardDirection.setVisibility(View.VISIBLE);
        }

        updatePathOnMap();
        Toast.makeText(requireContext(), "Tracking started", Toast.LENGTH_SHORT).show();
    }

    public void stopTracking() {
        isTracking = false;
        isPaused = false;
        deadReckoningEngine.stop();

        manualTurnControls.setVisibility(View.GONE);

        if (buttonPause != null) {
            buttonPause.setVisibility(View.GONE);
        }
        if (buttonStartStop != null) {
            buttonStartStop.setText(R.string.start);
            buttonStartStop.setIconResource(R.drawable.ic_play);
        }

        if (deadReckoningEngine.getCurrentTrip() != null) {
            tripStorage.saveTrip(deadReckoningEngine.getCurrentTrip());
            Toast.makeText(requireContext(), "Trip saved", Toast.LENGTH_SHORT).show();
        }

        Toast.makeText(requireContext(), "Tracking stopped", Toast.LENGTH_SHORT).show();
    }

    public boolean isTracking() {
        return isTracking;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), 
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void handleGPSUpdate(Location location) {
        if (!isAdded() || getView() == null || textGPSAccuracy == null) return;
        if (isPaused) return;
        if (lastKnownGPS != null && lastKnownGPS.getTime() > 0 && location.getTime() > 0
                && location.getTime() <= lastKnownGPS.getTime()) {
            return;
        }
        
        lastKnownGPS = location;
        double accuracy = location.hasAccuracy() ? location.getAccuracy() : Double.MAX_VALUE;
        boolean locationIsFresh = location.getTime() > 0
                && Math.abs(System.currentTimeMillis() - location.getTime()) <= GPS_STALE_TIMEOUT_MS;
        if (locationIsFresh && location.hasAccuracy() && accuracy <= 20f) {
            lastReliableGPSFix = new Location(location);
            requireContext().getSharedPreferences("last_location", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("latitude", Double.doubleToRawLongBits(location.getLatitude()))
                    .putLong("longitude", Double.doubleToRawLongBits(location.getLongitude()))
                    .putLong("time", location.getTime())
                    .apply();
        }
        
        if (forceNoGPSMode && autoNoGPSMode && locationIsFresh && accuracy < 20) {
            autoNoGPSMode = false;
            toggleNoGPSMode();
        }
        textGPSAccuracy.setText(location.hasAccuracy()
                ? String.format("%.1f m", accuracy)
                : "Unknown");
        
        GeoPoint gpsPoint = new GeoPoint(location.getLatitude(), location.getLongitude());

        // Keep a usable anchor even when the first fix is only fair accuracy. Without
        // this, GPS loss could switch modes with no point to show or continue from.
        if (isTracking && !forceNoGPSMode && locationIsFresh && accuracy <= 50f) {
            currentPosition = gpsPoint;
            if (lastDeadReckoningPoint == null) {
                lastDeadReckoningPoint = gpsPoint;
                lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
            }
        }
        
        if (forceNoGPSMode) {
            textGPSStatus.setText("No GPS");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            useGPSForTrace = false;
        } else if (!locationIsFresh) {
            textGPSStatus.setText("GPS: stale fix");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
            useGPSForTrace = false;
        } else if (accuracy < 15) {
            textGPSStatus.setText("GPS: OK");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorSuccess));
            useGPSForTrace = true;
            lastGPSMoveTime = System.currentTimeMillis();
        } else if (accuracy < 25) {
            textGPSStatus.setText("GPS: Fair");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
            useGPSForTrace = accuracy < 20;
        } else {
            textGPSStatus.setText("GPS: Low");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
            useGPSForTrace = false;
        }

        if (isTracking && !forceNoGPSMode && locationIsFresh) {
            updateGpsTravelBearing(location);
            deadReckoningEngine.calibrateWithGPS(location);

            if (useGPSForTrace && accuracy < 15) {
                if (lastGPSPoint == null) {
                    pathPoints.add(gpsPoint);
                } else {
                    double distance = distanceBetween(lastGPSPoint, gpsPoint);
                    if (distance > 1.0) {
                        pathPoints.add(gpsPoint);
                    }
                }
                lastGPSPoint = gpsPoint;
                currentPosition = gpsPoint;
                
                recentGPSPoints.add(gpsPoint);
                if (recentGPSPoints.size() > 20) {
                    recentGPSPoints.remove(0);
                }
                
                if (lastDeadReckoningPoint == null) {
                    lastDeadReckoningPoint = gpsPoint;
                    lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                    noGPSFixedHeading = deadReckoningEngine.getHeading();
                }
                
                updatePathOnMap();
            }

            if (startPosition == null && accuracy < 20) {
                startPosition = gpsPoint;
                mapView.getController().setCenter(startPosition);
            }
        }

        if (!isTracking && accuracy < 20) {
            currentPosition = gpsPoint;
        }
    }

    private double distanceBetween(GeoPoint p1, GeoPoint p2) {
        return p1.distanceToAsDouble(p2);
    }

    /**
     * Learn the direction of travel from GPS movement, which is independent of
     * the direction the top of the phone is facing.
     */
    private void updateGpsTravelBearing(Location location) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 25f) return;

        long now = SystemClock.elapsedRealtime();
        if (location.hasBearing() && location.hasSpeed()
                && location.getSpeed() >= 0.8f && location.getSpeed() <= 4.0f) {
            blendGpsTravelBearing(location.getBearing());
            gpsCourseUpdatedElapsedMs = now;
            lastGpsCourseFix = new Location(location);
            return;
        }

        if (lastGpsCourseFix == null) {
            lastGpsCourseFix = new Location(location);
            return;
        }

        double elapsedSeconds = (location.getTime() - lastGpsCourseFix.getTime()) / 1000.0;
        if (elapsedSeconds <= 0 || elapsedSeconds > 20) {
            lastGpsCourseFix = new Location(location);
            return;
        }

        double distanceMeters = lastGpsCourseFix.distanceTo(location);
        float previousAccuracy = lastGpsCourseFix.hasAccuracy()
                ? lastGpsCourseFix.getAccuracy() : location.getAccuracy();
        double minimumDistance = Math.max(6.0,
                Math.min(18.0, (previousAccuracy + location.getAccuracy()) * 0.5));
        if (distanceMeters >= minimumDistance) {
            double speed = distanceMeters / elapsedSeconds;
            if (speed >= 0.35 && speed <= 4.0) {
                blendGpsTravelBearing(lastGpsCourseFix.bearingTo(location));
                gpsCourseUpdatedElapsedMs = now;
                lastGpsCourseFix = new Location(location);
            } else if (speed > 4.0) {
                // Discard a GPS jump so it cannot teach a false walking direction.
                lastGpsCourseFix = new Location(location);
            }
        } else if (elapsedSeconds > 15) {
            lastGpsCourseFix = new Location(location);
        }
    }

    private void blendGpsTravelBearing(double bearing) {
        double normalized = normalizeBearing(bearing);
        if (!hasGpsTravelBearing) {
            gpsTravelBearing = normalized;
            hasGpsTravelBearing = true;
        } else {
            double difference = signedAngleDifference(normalized, gpsTravelBearing);
            double weight = Math.abs(difference) > 70.0 ? 0.65 : 0.30;
            gpsTravelBearing = normalizeBearing(gpsTravelBearing + difference * weight);
        }
        gpsTravelBearingSamples++;
    }

    private static double normalizeBearing(double bearing) {
        return (bearing % 360.0 + 360.0) % 360.0;
    }

    /** Return the shortest signed rotation from {@code from} to {@code to}. */
    private static double signedAngleDifference(double to, double from) {
        double difference = normalizeBearing(to) - normalizeBearing(from);
        while (difference > 180.0) difference -= 360.0;
        while (difference < -180.0) difference += 360.0;
        return difference;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values = event.values.clone();

        switch (event.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                headingEstimator.updateGravity(values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                headingEstimator.updateMagneticField(values);
                break;
            case Sensor.TYPE_GYROSCOPE:
                headingEstimator.updateGyroscope(values, event.timestamp);
                if (isTracking && !isPaused) {
                    deadReckoningEngine.updateSensors(null, null, values, null, event.timestamp);
                    updateUI();
                }
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                headingEstimator.updateRotationVector(values);
                if (isTracking && !isPaused) {
                    deadReckoningEngine.updateRotationVector(values);
                    updateUI();
                }
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                if (isTracking && !isPaused && activeStepMode == StepCounterPreferences.StepMode.ANDROID) {
                    lastHardwareStepElapsedMs = SystemClock.elapsedRealtime();
                    deadReckoningEngine.addAndroidStep();
                    updateUI();
                }
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                if (isTracking && !isPaused) {
                    processAcceleration(values, headingEstimator.getGravity(), event.timestamp);
                    fallbackFromSilentHardwareStepSensor();
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                if (usingAccelerometerFallback && isTracking && !isPaused) {
                    float[] gravity = new float[3];
                    float[] linearAcceleration = new float[3];
                    final float gravitySmoothing = 0.8f;
                    for (int axis = 0; axis < 3; axis++) {
                        if (!fallbackGravityInitialized) fallbackGravity[axis] = values[axis];
                        else fallbackGravity[axis] = gravitySmoothing * fallbackGravity[axis]
                                + (1f - gravitySmoothing) * values[axis];
                        gravity[axis] = fallbackGravity[axis];
                        linearAcceleration[axis] = values[axis] - gravity[axis];
                    }
                    fallbackGravityInitialized = true;
                    headingEstimator.updateGravity(gravity);
                    processAcceleration(linearAcceleration, gravity, event.timestamp);
                    fallbackFromSilentHardwareStepSensor();
                }
                break;
        }
    }

    private void processAcceleration(float[] linearAcceleration, float[] gravity, long timestamp) {
        deadReckoningEngine.updateSensors(
                gravity,
                headingEstimator.getMagneticField(),
                null,
                linearAcceleration,
                timestamp
        );
        updateUI();
    }

    private void fallbackFromSilentHardwareStepSensor() {
        if (activeStepMode != StepCounterPreferences.StepMode.ANDROID
                || deadReckoningEngine.getDynamicStepCount() == 0) return;
        long now = SystemClock.elapsedRealtime();
        long lastHardwareEvent = lastHardwareStepElapsedMs == 0
                ? trackingStartedElapsedMs : lastHardwareStepElapsedMs;
        if (now - lastHardwareEvent < 5000L) return;

        activeStepMode = StepCounterPreferences.StepMode.DYNAMIC;
        deadReckoningEngine.setStepMode(activeStepMode);
        if (sensorStepDetector != null) sensorManager.unregisterListener(this, sensorStepDetector);
        Toast.makeText(requireContext(), "Hardware step sensor is quiet; switched to motion-based counting",
                Toast.LENGTH_LONG).show();
        updateUI();
    }

    private void updateUI() {
        if (getActivity() == null) return;
        
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || getView() == null || textSteps == null || deadReckoningEngine == null) return;
            if (isTracking && !forceNoGPSMode && lastKnownGPS != null
                    && System.currentTimeMillis() - lastKnownGPS.getTime() > GPS_STALE_TIMEOUT_MS) {
                textGPSStatus.setText("GPS lost — estimating");
                textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
                autoNoGPSMode = true;
                toggleNoGPSMode();
            }
            textSteps.setText(String.valueOf(deadReckoningEngine.getStepCount()));
            textDistance.setText(String.format("%.2f m", deadReckoningEngine.getDistance()));
            double heading = deadReckoningEngine.getHeading();
            if (forceNoGPSMode) {
                noGPSFixedHeading = hasOfflineHeadingOffset
                        ? normalizeBearing(heading + offlineHeadingOffset)
                        : normalizeBearing(heading);
            }
            double displayHeading = forceNoGPSMode ? noGPSFixedHeading : normalizeBearing(heading);
            textHeading.setText(String.format("%.1f°", displayHeading));

            if (imageDirectionArrow != null && cardDirection != null && isTracking) {
                // Rotate the arrow clockwise by the heading so it points in the
                // direction the device is travelling. The ic_arrow_up drawable
                // points North (up) at 0°, and Android's setRotation() is
                // clockwise-positive, which matches the compass bearing convention.
                float rotation = (float) displayHeading;
                imageDirectionArrow.setRotation(rotation);
                cardDirection.setVisibility(View.VISIBLE);
            }

            // Keep the estimated track aligned with the live compass heading.
            // Manual turns remain stable because the engine returns its selected heading.
            if (forceNoGPSMode) {
                double uncertaintyMeters = getEstimatedUncertaintyMeters();
                textGPSStatus.setText(getOfflinePositionStatus());
                textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
                textGPSAccuracy.setText(String.format("±%.0f m", uncertaintyMeters));
            }

            if (!isManualMode && deadReckoningEngine.isTurning()) {
                double turnAmount = deadReckoningEngine.getAccumulatedHeadingChange();
                // Positive accumulated heading change = clockwise = turning Right.
                // Negative accumulated heading change = counter-clockwise = turning Left.
                if (turnAmount > 0) {
                    showTurnIndicator("Right " + String.format("%.0f°", Math.abs(turnAmount)));
                } else {
                    showTurnIndicator("Left " + String.format("%.0f°", Math.abs(turnAmount)));
                }
                
            }

            if (isPaused) return;

            if (isTracking && forceNoGPSMode && lastDeadReckoningPoint != null) {
                double totalDistance = deadReckoningEngine.getDistance();
                double incrementalDistance = totalDistance - lastDeadReckoningDistance;
                
                if (incrementalDistance > 0.1 && incrementalDistance < 50.0) {
                    noGPSDistanceSinceAnchor += incrementalDistance;
                    GeoPoint newPoint = calculateEstimatedPosition(
                            lastDeadReckoningPoint.getLatitude(),
                            lastDeadReckoningPoint.getLongitude(),
                            noGPSFixedHeading,
                            incrementalDistance
                    );

                    if (newPoint != null) {
                        GeoPoint loopClosure = findNearbyRecordedPoint(newPoint);
                        if (loopClosure != null) {
                            newPoint = loopClosure;
                            noGPSDistanceSinceAnchor = 0;
                            resetLoopClosureEvidence();
                            Location loopAnchor = new Location("dead_reckoning_loop_closure");
                            loopAnchor.setLatitude(newPoint.getLatitude());
                            loopAnchor.setLongitude(newPoint.getLongitude());
                            loopAnchor.setAccuracy((float) getEstimatedUncertaintyMeters());
                            loopAnchor.setTime(System.currentTimeMillis());
                            deadReckoningEngine.reanchorAtLocation(loopAnchor);
                        }
                        noGPSPathPoints.add(newPoint);
                        lastDeadReckoningPoint = newPoint;
                        currentPosition = newPoint;
                        if (estimatedPositionMarker != null) {
                            estimatedPositionMarker.setPosition(newPoint);
                        }
                        if (mapView != null) {
                            mapView.getController().setCenter(newPoint);
                        }
                        updatePathOnMap();
                    }
                }
                lastDeadReckoningDistance = totalDistance;
            }
        });
    }

    private double getEstimatedUncertaintyMeters() {
        if (deadReckoningEngine == null) return 5.0;
        // Position error grows with the distance walked without a new satellite fix.
        // Show a conservative estimate instead of presenting dead reckoning as GPS-grade.
        return Math.min(500.0, 10.0 + deadReckoningEngine.getDistance() * 0.15);
    }

    private String getOfflinePositionStatus() {
        if (hasOfflineHeadingOffset) {
            return autoNoGPSMode ? "GPS lost · course estimate" : "Course estimate";
        }
        return autoNoGPSMode ? "GPS lost · sensor estimate" : "Offline estimate";
    }

    /** Snap only to this offline segment's origin after a confirmed return to it. */
    @Nullable
    private GeoPoint findNearbyRecordedPoint(GeoPoint candidate) {
        if (candidate == null || noGPSLoopAnchor == null
                || noGPSDistanceSinceAnchor < 20.0 || noGPSPathPoints.size() < 12
                || lastDeadReckoningPoint == null) {
            resetLoopClosureEvidence();
            return null;
        }

        double distanceToAnchor = candidate.distanceToAsDouble(noGPSLoopAnchor);
        double previousDistanceToAnchor = lastDeadReckoningPoint.distanceToAsDouble(noGPSLoopAnchor);
        boolean approachingAnchor = distanceToAnchor < previousDistanceToAnchor - 0.15;
        if (distanceToAnchor > LOOP_CLOSURE_RADIUS_METERS || !approachingAnchor) {
            resetLoopClosureEvidence();
            return null;
        }

        loopClosureObservations++;
        return loopClosureObservations >= LOOP_CLOSURE_REQUIRED_OBSERVATIONS
                ? noGPSLoopAnchor : null;
    }

    private void resetLoopClosureEvidence() {
        loopClosureObservations = 0;
    }

    private void restoreLastKnownLocation() {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("last_location", Context.MODE_PRIVATE);
        long fixTime = prefs.getLong("time", 0L);
        if (fixTime == 0L || System.currentTimeMillis() - fixTime > 24L * 60L * 60L * 1000L) return;

        double latitude = Double.longBitsToDouble(prefs.getLong("latitude", 0L));
        double longitude = Double.longBitsToDouble(prefs.getLong("longitude", 0L));
        if (Double.isNaN(latitude) || Double.isInfinite(latitude)
                || Double.isNaN(longitude) || Double.isInfinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return;

        Location savedFix = new Location("saved");
        savedFix.setLatitude(latitude);
        savedFix.setLongitude(longitude);
        savedFix.setAccuracy(100f);
        savedFix.setTime(fixTime);
        lastKnownGPS = savedFix;
        lastReliableGPSFix = new Location(savedFix);
    }

    private void saveVisibleMapAreaOffline() {
        if (!isAdded() || mapView == null || !mapView.isLaidOut()) {
            if (isAdded()) Toast.makeText(requireContext(), "Map is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        if (offlineMapDownloadRunning) {
            Toast.makeText(requireContext(), "Offline map download is already running", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            BoundingBox visibleArea = mapView.getBoundingBox();
            int zoom = (int) Math.round(mapView.getZoomLevelDouble());
            int minZoom = Math.max(3, zoom - 1);
            int maxZoom = Math.min(18, zoom);
            CacheManager cacheManager = DeadReckoningApp.getCacheManager(mapView);
            int tileCount = cacheManager.possibleTilesInArea(visibleArea, minZoom, maxZoom);
            if (tileCount <= 0) {
                Toast.makeText(requireContext(), "No map tiles found in this area", Toast.LENGTH_SHORT).show();
                return;
            }
            if (tileCount > 3000) {
                Toast.makeText(requireContext(), "Zoom in to save a smaller offline map area", Toast.LENGTH_LONG).show();
                return;
            }

            offlineMapDownloadRunning = true;
            Toast.makeText(requireContext(), "Saving " + tileCount + " map tiles for offline use…",
                    Toast.LENGTH_LONG).show();
            CacheManagerCallback callback = new CacheManagerCallback() {
                @Override public void onTaskComplete() {
                    offlineMapDownloadRunning = false;
                    if (isAdded()) Toast.makeText(requireContext(), "Offline map area saved",
                            Toast.LENGTH_SHORT).show();
                }

                @Override public void updateProgress(int progress, int currentZoom, int minZoom, int maxZoom) { }
                @Override public void downloadStarted() { }
                @Override public void setPossibleTilesInArea(int possibleTiles) { }

                @Override public void onTaskFailed(int errors) {
                    offlineMapDownloadRunning = false;
                    if (isAdded()) Toast.makeText(requireContext(),
                            "Map download failed. Check internet and try again.", Toast.LENGTH_LONG).show();
                }
            };
            cacheManager.downloadAreaAsyncNoUI(requireContext(), visibleArea, minZoom, maxZoom, callback);
        } catch (RuntimeException error) {
            offlineMapDownloadRunning = false;
            Toast.makeText(requireContext(), "Could not start offline map download", Toast.LENGTH_LONG).show();
            android.util.Log.e("MapFragment", "Unable to start offline tile download", error);
        }
    }

    private void openDialOverlay() {
        if (dialOverlayContainer == null || degreeDialView == null) return;
        
        float currentHeading = (float) noGPSFixedHeading;
        if (deadReckoningEngine != null && isTracking) {
            currentHeading = (float) deadReckoningEngine.getHeading();
        }
        
        degreeDialView.setDegree(currentHeading);
        dialOverlayContainer.setVisibility(View.VISIBLE);
    }

    private void closeDialOverlay() {
        if (dialOverlayContainer != null) {
            dialOverlayContainer.setVisibility(View.GONE);
        }
    }

    private void confirmDialSelection() {
        if (degreeDialView != null && deadReckoningEngine != null && isTracking) {
            float selectedDegree = degreeDialView.getDegree();
            
            if (forceNoGPSMode) {
                // A dial selection is an explicit direction override, so stop
                // applying the automatically learned GPS-course offset.
                hasOfflineHeadingOffset = false;
                deadReckoningEngine.setTravelHeadingOffset(0.0);
                noGPSFixedHeading = selectedDegree;
                deadReckoningEngine.setFixedHeading(selectedDegree);
                showTurnIndicator(String.format("%.0f°", selectedDegree));
            } else if (isManualMode) {
                deadReckoningEngine.setFixedHeading(selectedDegree);
            }
        }
        
        closeDialOverlay();
    }

    private GeoPoint calculateEstimatedPosition(double lat, double lon, double heading, double distanceMeters) {
        // Move by ground distance on the Earth's surface. This keeps metres consistent
        // across latitudes instead of treating the map as a flat, fixed-scale drawing.
        final double earthRadius = 6_371_000.0;
        double angularDistance = distanceMeters / earthRadius;
        double bearing = Math.toRadians(heading);
        double startLat = Math.toRadians(lat);
        double startLon = Math.toRadians(lon);

        double endLat = Math.asin(Math.sin(startLat) * Math.cos(angularDistance)
                + Math.cos(startLat) * Math.sin(angularDistance) * Math.cos(bearing));
        double endLon = startLon + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(startLat),
                Math.cos(angularDistance) - Math.sin(startLat) * Math.sin(endLat));
        endLon = (endLon + 3 * Math.PI) % (2 * Math.PI) - Math.PI;

        return new GeoPoint(Math.toDegrees(endLat), Math.toDegrees(endLon));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView == null) return;
        mapView.onResume();
        registerSensors();
        startLocationUpdates();
        
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
        updateTurnModeUI();
        
        try {
            requireContext().registerReceiver(screenStateReceiver, screenStateFilter);
        } catch (Exception ignored) {}
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
        // Keep sensors registered for background tracking
        // sensorManager.unregisterListener(this);
        
        try {
            requireContext().unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {}
        
        stopLocationUpdates();
    }

    @Override
    public void onDestroyView() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        stopLocationUpdates();
        if (cardTurn != null) cardTurn.removeCallbacks(hideTurnRunnable);
        try {
            requireContext().unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {}
        mapView = null;
        super.onDestroyView();
    }

    private boolean isScreenOn = true;
    private final IntentFilter screenStateFilter = new IntentFilter();
    {
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
    }

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
            }
        }
    };

    private void toggleAddMarkerMode() {
        isAddingMarker = !isAddingMarker;
        if (isAddingMarker) {
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorSuccess));
            Toast.makeText(requireContext(), R.string.tap_location, Toast.LENGTH_SHORT).show();
        } else {
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            pendingMarkerPosition = null;
        }
    }

    private void showMarkerDialog(GeoPoint position) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_marker, null);
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setView(dialogView);
        
        android.app.AlertDialog dialog = builder.create();
        
        TextView[] emojiViews = {
            dialogView.findViewById(R.id.emoji_pin),
            dialogView.findViewById(R.id.emoji_pushpin),
            dialogView.findViewById(R.id.emoji_home),
            dialogView.findViewById(R.id.emoji_building),
            dialogView.findViewById(R.id.emoji_restaurant),
            dialogView.findViewById(R.id.emoji_pizza),
            dialogView.findViewById(R.id.emoji_coffee),
            dialogView.findViewById(R.id.emoji_shopping),
            dialogView.findViewById(R.id.emoji_hospital),
            dialogView.findViewById(R.id.emoji_fuel),
            dialogView.findViewById(R.id.emoji_hotel),
            dialogView.findViewById(R.id.emoji_park),
            dialogView.findViewById(R.id.emoji_car),
            dialogView.findViewById(R.id.emoji_plane),
            dialogView.findViewById(R.id.emoji_star),
            dialogView.findViewById(R.id.emoji_heart),
            dialogView.findViewById(R.id.emoji_red_dot),
            dialogView.findViewById(R.id.emoji_green_dot)
        };
        
        String[] emojis = {"📍", "📌", "🏠", "🏢", "🍔", "🍕", "☕", "🛒", "🏥", "⛽", "🏨", "🏞", "🚗", "✈️", "⭐", "❤️", "🔴", "🟢"};
        
        for (int i = 0; i < emojiViews.length; i++) {
            final int index = i;
            emojiViews[i].setOnClickListener(v -> {
                selectedEmoji = emojis[index];
                for (TextView tv : emojiViews) {
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                emojiViews[index].setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimaryLight));
            });
        }
        
        emojiViews[0].setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimaryLight));
        
        com.google.android.material.textfield.TextInputEditText editLabel = dialogView.findViewById(R.id.editMarkerLabel);
        
        dialogView.findViewById(R.id.buttonCancelMarker).setOnClickListener(v -> {
            dialog.dismiss();
            isAddingMarker = false;
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            pendingMarkerPosition = null;
        });
        
        dialogView.findViewById(R.id.buttonConfirmMarker).setOnClickListener(v -> {
            String label = editLabel.getText() != null ? editLabel.getText().toString().trim() : "";
            
            Marker marker = new Marker(position.getLatitude(), position.getLongitude(), selectedEmoji);
            if (!label.isEmpty()) {
                marker.setLabel(label);
            }
            
            markerStorage.saveMarker(marker);
            addMarkerToMap(marker);
            
            dialog.dismiss();
            isAddingMarker = false;
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            Toast.makeText(requireContext(), R.string.marker_added, Toast.LENGTH_SHORT).show();
        });
        
        dialog.show();
    }

    private void addMarkerToMap(Marker marker) {
        org.osmdroid.views.overlay.Marker mapMarker = new org.osmdroid.views.overlay.Marker(mapView);
        mapMarker.setPosition(new GeoPoint(marker.getLatitude(), marker.getLongitude()));
        
        String title = marker.getEmoji();
        if (marker.getLabel() != null && !marker.getLabel().isEmpty()) {
            title = marker.getLabel() + " " + marker.getEmoji();
        }
        mapMarker.setTitle(title);
        mapMarker.setSnippet(marker.getEmoji());
        mapMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM);
        
        mapMarker.setOnMarkerClickListener((m, mv) -> {
            m.showInfoWindow();
            return true;
        });

        mapMarker.setOnMarkerDragListener(new org.osmdroid.views.overlay.Marker.OnMarkerDragListener() {
            @Override
            public void onMarkerDrag(org.osmdroid.views.overlay.Marker m) {}

            @Override
            public void onMarkerDragEnd(org.osmdroid.views.overlay.Marker m) {
                GeoPoint pos = m.getPosition();
                for (int i = 0; i < markerOverlays.size(); i++) {
                    if (markerOverlays.get(i) == m) {
                        Marker modelMarker = mapMarkers.get(i);
                        modelMarker.setLatitude(pos.getLatitude());
                        modelMarker.setLongitude(pos.getLongitude());
                        markerStorage.updateMarker(modelMarker);
                        break;
                    }
                }
            }

            @Override
            public void onMarkerDragStart(org.osmdroid.views.overlay.Marker m) {}
        });

        mapView.getOverlays().add(mapMarker);
        markerOverlays.add(mapMarker);
        mapMarkers.add(marker);
        mapView.invalidate();
    }

    private void loadMarkers() {
        List<Marker> markers = markerStorage.getAllMarkers();
        for (Marker marker : markers) {
            addMarkerToMap(marker);
        }
    }

    private void showClearAllDialog() {
        String[] options = {getString(R.string.clear_tracks_only), getString(R.string.clear_markers_only), getString(R.string.clear_everything)};
        
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_confirmation)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        clearTracksOnly();
                        Toast.makeText(requireContext(), R.string.trips_cleared, Toast.LENGTH_SHORT).show();
                        break;
                    case 1:
                        clearMarkersOnly();
                        Toast.makeText(requireContext(), R.string.markers_cleared, Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        clearTracksOnly();
                        clearMarkersOnly();
                        Toast.makeText(requireContext(), R.string.everything_cleared, Toast.LENGTH_SHORT).show();
                        break;
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void clearTracksOnly() {
        pathPoints.clear();
        noGPSPathPoints.clear();
        noGPSLoopAnchor = null;
        noGPSDistanceSinceAnchor = 0;
        resetLoopClosureEvidence();
        if (gpsLossMarker != null) gpsLossMarker.setEnabled(false);
        updatePathOnMap();
    }

    private void clearMarkersOnly() {
        for (org.osmdroid.views.overlay.Marker m : markerOverlays) {
            mapView.getOverlays().remove(m);
        }
        markerOverlays.clear();
        mapMarkers.clear();
        markerStorage.clearAllMarkers();
        mapView.invalidate();
    }
}
