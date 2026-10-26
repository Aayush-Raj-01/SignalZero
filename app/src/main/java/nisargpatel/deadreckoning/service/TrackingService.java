package nisargpatel.deadreckoning.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.activity.MainContainerActivity;
import nisargpatel.deadreckoning.model.Trip;
import nisargpatel.deadreckoning.model.TurnEvent;
import nisargpatel.deadreckoning.preferences.TurnModePreferences;
import nisargpatel.deadreckoning.sensor.DeadReckoningEngine;
import nisargpatel.deadreckoning.sensor.PreciseHeadingEstimator;
import nisargpatel.deadreckoning.storage.TripStorage;

public class TrackingService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "tracking_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START = "nisargpatel.deadreckoning.action.START";
    public static final String ACTION_STOP = "nisargpatel.deadreckoning.action.STOP";

    private final IBinder binder = new LocalBinder();

    private SensorManager sensorManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private PowerManager.WakeLock wakeLock;

    private Sensor sensorGravity;
    private Sensor sensorMagnetic;
    private Sensor sensorGyroscope;
    private Sensor sensorLinearAcceleration;

    private DeadReckoningEngine deadReckoningEngine;
    private PreciseHeadingEstimator headingEstimator;
    private TripStorage tripStorage;
    private TurnModePreferences turnModePrefs;

    private boolean isTracking = false;
    private boolean isManualMode = false;
    private boolean isScreenOn = true;

    private List<GeoPoint> pathPoints = new ArrayList<>();
    private Trip currentTrip = null;

    private int gpsCalibrationPoints = 0;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
            }
        }
    };

    public class LocalBinder extends Binder {
        public TrackingService getService() {
            return TrackingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initSensors();
        initLocation();
        initEngine();
        initWakeLock();
        registerScreenReceiver();
    }

    private void initWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, 
                "DeadReckoning:TrackingWakeLock");
        }
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startTracking();
            } else if (ACTION_STOP.equals(action)) {
                stopTracking();
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_in_progress),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.tracking_notification_desc));
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    }

    private void initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    handleGPSUpdate(location);
                }
            }
        };
    }

    private void initEngine() {
        deadReckoningEngine = new DeadReckoningEngine();
        headingEstimator = new PreciseHeadingEstimator();
        tripStorage = new TripStorage(this);
        turnModePrefs = new TurnModePreferences(this);
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
    }

    public void startTracking() {
        if (isTracking) return;

        isTracking = true;
        deadReckoningEngine.start();
        pathPoints.clear();
        gpsCalibrationPoints = 0;

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 60 * 1000L);
        }

        registerSensors();
        startLocationUpdates();
        startForeground();

        updateNotification(getString(R.string.tracking_in_progress), getString(R.string.tracking_status_format, 0, 0.00));
    }

    public void stopTracking() {
        if (!isTracking) return;

        isTracking = false;
        deadReckoningEngine.stop();

        sensorManager.unregisterListener(this);
        fusedLocationClient.removeLocationUpdates(locationCallback);

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if (deadReckoningEngine.getCurrentTrip() != null) {
            tripStorage.saveTrip(deadReckoningEngine.getCurrentTrip());
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public void calibrateGPS() {
        gpsCalibrationPoints = 0;
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
        if (sensorLinearAcceleration != null) {
            sensorManager.registerListener(this, sensorLinearAcceleration, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void startForeground() {
        Intent notificationIntent = new Intent(this, MainContainerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.tracking_in_progress) + "...")
                .setSmallIcon(R.drawable.ic_directions_walk)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainContainerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_directions_walk)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void handleGPSUpdate(Location location) {
        if (!isTracking) return;

        if (location.getAccuracy() < 15) {
            deadReckoningEngine.calibrateWithGPS(location);
            gpsCalibrationPoints++;
        }

        int steps = deadReckoningEngine.getStepCount();
        double distance = deadReckoningEngine.getDistance();
        updateNotification(getString(R.string.app_name), getString(R.string.tracking_status_format, steps, distance));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isTracking) return;

        float[] values = event.values.clone();

        switch (event.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                headingEstimator.updateGravity(values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                headingEstimator.updateMagneticField(values);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                deadReckoningEngine.updateSensors(
                        headingEstimator.getGravity(),
                        headingEstimator.getMagneticField(),
                        headingEstimator.getGyroscope(),
                        values,
                        event.timestamp
                );
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public boolean isTracking() {
        return isTracking;
    }

    public int getStepCount() {
        return deadReckoningEngine.getStepCount();
    }

    public double getDistance() {
        return deadReckoningEngine.getDistance();
    }

    public double getHeading() {
        return deadReckoningEngine.getHeading();
    }

    public int getCalibrationPoints() {
        return gpsCalibrationPoints;
    }

    public void turnLeft() {
        if (isTracking) {
            deadReckoningEngine.turnLeft();
        }
    }

    public void turnRight() {
        if (isTracking) {
            deadReckoningEngine.turnRight();
        }
    }

    public void turnAround() {
        if (isTracking) {
            deadReckoningEngine.turnAround();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        stopTracking();
    }
}
