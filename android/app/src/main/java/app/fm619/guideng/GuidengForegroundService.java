package app.fm619.guideng;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GuidengForegroundService extends Service {
    public static final String ACTION_CONFIGURE = "app.fm619.guideng.CONFIGURE_LOCATION_SHARING";
    public static final String ACTION_CLEAR = "app.fm619.guideng.CLEAR_LOCATION_SHARING";
    public static final String EXTRA_SERVER_URL = "server_url";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    private static final String CHANNEL_ID = "guideng_location_sharing";
    private static final int NOTIFICATION_ID = 619;
    private static final String PREFS_NAME = "guideng.location_sharing";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final long LOCATION_INTERVAL_MS = 60_000L;
    private static final float LOCATION_DISTANCE_METERS = 15f;
    private static final long WATCHDOG_INTERVAL_MS = 120_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final LocationListener locationListener = this::uploadLocation;
    private LocationManager locationManager;
    private Session session;
    private boolean requestingUpdates;
    private volatile long lastUploadedAt;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            uploadNewestLastKnownLocation();
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        session = readSession();
        if (session != null) {
            registerDevice(session);
        }
        startLocationUpdatesIfReady();
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        if (intent != null) {
            if (ACTION_CONFIGURE.equals(intent.getAction())) {
                session = sessionFromIntent(intent);
                if (session != null) {
                    writeSession(session);
                    registerDevice(session);
                    startLocationUpdatesIfReady();
                    uploadNewestLastKnownLocation();
                }
            } else if (ACTION_CLEAR.equals(intent.getAction())) {
                clearSession();
                stopLocationUpdates();
                stopSelf();
            } else if (session == null) {
                session = readSession();
                startLocationUpdatesIfReady();
            }
        }
        if (session == null && (intent == null || !ACTION_CONFIGURE.equals(intent.getAction()))) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(watchdog);
        stopLocationUpdates();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdatesIfReady() {
        if (requestingUpdates || session == null || locationManager == null || !hasLocationPermission()) {
            return;
        }

        List<String> providers = locationManager.getProviders(true);
        if (providers == null || providers.isEmpty()) {
            return;
        }

        for (String provider : providers) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MS,
                    LOCATION_DISTANCE_METERS,
                    locationListener,
                    Looper.getMainLooper()
                );
                requestingUpdates = true;
            } catch (IllegalArgumentException | SecurityException ignored) {
            }
        }
    }

    private void stopLocationUpdates() {
        if (!requestingUpdates || locationManager == null) {
            return;
        }

        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
        }
        requestingUpdates = false;
    }

    @SuppressLint("MissingPermission")
    private void uploadNewestLastKnownLocation() {
        if (session == null || locationManager == null || !hasLocationPermission()) {
            return;
        }

        List<String> providers = locationManager.getProviders(true);
        if (providers == null || providers.isEmpty()) {
            return;
        }

        Location best = null;
        for (String provider : providers) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            } catch (IllegalArgumentException | SecurityException ignored) {
            }
        }

        if (best != null && best.getTime() > lastUploadedAt) {
            uploadLocation(best);
        }
    }

    private void uploadLocation(Location location) {
        Session activeSession = session;
        if (activeSession == null || location == null || location.getTime() <= lastUploadedAt) {
            return;
        }

        long capturedAt = location.getTime();
        networkExecutor.execute(() -> {
            try {
                postJson(activeSession, "/api/devices/" + activeSession.deviceId + "/location", locationJson(location));
                lastUploadedAt = Math.max(lastUploadedAt, capturedAt);
            } catch (IOException | JSONException ignored) {
            }
        });
    }

    private void registerDevice(Session activeSession) {
        networkExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("id", activeSession.deviceId);
                payload.put("name", activeSession.deviceName);
                payload.put("platform", "Android");
                postJson(activeSession, "/api/devices", payload);
            } catch (IOException | JSONException ignored) {
            }
        });
    }

    private JSONObject locationJson(Location location) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("latitude", location.getLatitude());
        payload.put("longitude", location.getLongitude());
        payload.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
        payload.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
        payload.put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
        payload.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
        payload.put("captured_at", isoTimestamp(location.getTime()));
        return payload;
    }

    private void postJson(Session activeSession, String path, JSONObject payload) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(activeSession.serverUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + activeSession.token);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                readFully(connection.getErrorStream());
                throw new IOException("Location upload failed with HTTP " + status);
            } else {
                readFully(connection.getInputStream());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void readFully(@Nullable InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // Drain the stream so the HTTP connection can close cleanly.
            }
        }
    }

    private String isoTimestamp(long timeMillis) {
        java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        formatter.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return formatter.format(new java.util.Date(timeMillis));
    }

    private Session sessionFromIntent(Intent intent) {
        String serverUrl = cleanServerUrl(intent.getStringExtra(EXTRA_SERVER_URL));
        String token = intent.getStringExtra(EXTRA_TOKEN);
        String deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
        String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);

        if (isBlank(serverUrl) || isBlank(token) || isBlank(deviceId) || isBlank(deviceName)) {
            return null;
        }
        return new Session(serverUrl, token, deviceId, deviceName);
    }

    private Session readSession() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String serverUrl = prefs.getString(KEY_SERVER_URL, "");
        String token = prefs.getString(KEY_TOKEN, "");
        String deviceId = prefs.getString(KEY_DEVICE_ID, "");
        String deviceName = prefs.getString(KEY_DEVICE_NAME, "");
        if (isBlank(serverUrl) || isBlank(token) || isBlank(deviceId) || isBlank(deviceName)) {
            return null;
        }
        return new Session(serverUrl, token, deviceId, deviceName);
    }

    private void writeSession(Session activeSession) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, activeSession.serverUrl)
            .putString(KEY_TOKEN, activeSession.token)
            .putString(KEY_DEVICE_ID, activeSession.deviceId)
            .putString(KEY_DEVICE_NAME, activeSession.deviceName)
            .apply();
    }

    private void clearSession() {
        session = null;
        lastUploadedAt = 0L;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
    }

    private String cleanServerUrl(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Notification createNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.foreground_service_channel),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private static class Session {
        final String serverUrl;
        final String token;
        final String deviceId;
        final String deviceName;

        Session(String serverUrl, String token, String deviceId, String deviceName) {
            this.serverUrl = serverUrl;
            this.token = token;
            this.deviceId = deviceId;
            this.deviceName = deviceName;
        }
    }
}
