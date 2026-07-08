package app.fm619.guideng;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {
    private static final int REQUEST_RUNTIME_PERMISSIONS = 6190;
    private static final int REQUEST_BACKGROUND_LOCATION = 6191;
    private static final String PREFS_NAME = "guideng.permissions";
    private static final String KEY_ASKED_BATTERY = "asked_battery_optimization";
    private static final String KEY_OPENED_BACKGROUND_SETTINGS = "opened_background_settings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installNativeLocationBridge();
        requestRuntimePermissions();
        startLocationForegroundServiceIfAllowed();
        requestBackgroundLocationIfNeeded();
        requestBatteryOptimizationExemptionOnce();
    }

    @Override
    public void onResume() {
        super.onResume();
        startLocationForegroundServiceIfAllowed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            startLocationForegroundServiceIfAllowed();
            requestBackgroundLocationIfNeeded();
            requestBatteryOptimizationExemptionOnce();
        }
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_RUNTIME_PERMISSIONS);
        }
    }

    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasForegroundLocationPermission()) {
            return;
        }
        if (hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            return;
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.ACCESS_BACKGROUND_LOCATION }, REQUEST_BACKGROUND_LOCATION);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_OPENED_BACKGROUND_SETTINGS, false)) {
            return;
        }

        prefs.edit().putBoolean(KEY_OPENED_BACKGROUND_SETTINGS, true).apply();
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void requestBatteryOptimizationExemptionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED_BATTERY, false)) {
            return;
        }

        prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply();
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startLocationForegroundServiceIfAllowed() {
        if (!hasForegroundLocationPermission()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            return;
        }

        Intent intent = new Intent(this, GuidengForegroundService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private boolean hasForegroundLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void installNativeLocationBridge() {
        if (bridge == null || bridge.getWebView() == null) {
            return;
        }
        bridge.getWebView().addJavascriptInterface(new NativeLocationBridge(), "GuidengNative");
    }

    private class NativeLocationBridge {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @JavascriptInterface
        public void getCurrentLocation(String requestId) {
            handler.post(() -> getCurrentLocationOnMainThread(requestId));
        }

        @JavascriptInterface
        public void configureLocationSharing(String sessionJson) {
            handler.post(() -> configureLocationSharingOnMainThread(sessionJson));
        }

        @JavascriptInterface
        public void clearLocationSharing() {
            handler.post(() -> {
                Intent intent = new Intent(MainActivity.this, GuidengForegroundService.class);
                intent.setAction(GuidengForegroundService.ACTION_CLEAR);
                ContextCompat.startForegroundService(MainActivity.this, intent);
            });
        }

        private void configureLocationSharingOnMainThread(String sessionJson) {
            try {
                JSONObject session = new JSONObject(sessionJson);
                Intent intent = new Intent(MainActivity.this, GuidengForegroundService.class);
                intent.setAction(GuidengForegroundService.ACTION_CONFIGURE);
                intent.putExtra(GuidengForegroundService.EXTRA_SERVER_URL, session.optString("serverUrl"));
                intent.putExtra(GuidengForegroundService.EXTRA_TOKEN, session.optString("token"));
                intent.putExtra(GuidengForegroundService.EXTRA_DEVICE_ID, session.optString("deviceId"));
                intent.putExtra(GuidengForegroundService.EXTRA_DEVICE_NAME, session.optString("deviceName"));
                ContextCompat.startForegroundService(MainActivity.this, intent);
                requestBackgroundLocationIfNeeded();
                requestBatteryOptimizationExemptionOnce();
            } catch (JSONException ignored) {
            }
        }

        @SuppressLint("MissingPermission")
        private void getCurrentLocationOnMainThread(String requestId) {
            if (!hasForegroundLocationPermission()) {
                sendLocationResult(requestId, locationError("permission_denied", "Location permission is not granted."));
                requestRuntimePermissions();
                return;
            }

            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager == null) {
                sendLocationResult(requestId, locationError("unavailable", "Location service is not available."));
                return;
            }

            List<String> providers = locationManager.getProviders(true);
            if (providers == null || providers.isEmpty()) {
                sendLocationResult(requestId, locationError("unavailable", "No enabled location provider."));
                return;
            }

            Location lastKnownLocation = newestLastKnownLocation(locationManager, providers);
            if (lastKnownLocation != null && System.currentTimeMillis() - lastKnownLocation.getTime() <= 120_000) {
                sendLocationResult(requestId, locationPayload(lastKnownLocation));
                return;
            }

            final LocationListener[] listenerRef = new LocationListener[1];
            Runnable timeout = () -> {
                if (listenerRef[0] != null) {
                    locationManager.removeUpdates(listenerRef[0]);
                    listenerRef[0] = null;
                }
                Location fallback = newestLastKnownLocation(locationManager, providers);
                sendLocationResult(
                    requestId,
                    fallback != null ? locationPayload(fallback) : locationError("timeout", "Location request timed out.")
                );
            };

            LocationListener listener = location -> {
                if (listenerRef[0] == null) {
                    return;
                }
                handler.removeCallbacks(timeout);
                locationManager.removeUpdates(listenerRef[0]);
                listenerRef[0] = null;
                sendLocationResult(requestId, locationPayload(location));
            };
            listenerRef[0] = listener;

            try {
                for (String provider : providers) {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
                }
                handler.postDelayed(timeout, 25_000);
            } catch (IllegalArgumentException | SecurityException err) {
                handler.removeCallbacks(timeout);
                sendLocationResult(requestId, locationError("unavailable", err.getMessage()));
            }
        }

        @SuppressLint("MissingPermission")
        private Location newestLastKnownLocation(LocationManager locationManager, List<String> providers) {
            Location best = null;
            for (String provider : providers) {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            }
            return best;
        }

        private JSONObject locationPayload(Location location) {
            JSONObject payload = new JSONObject();
            JSONObject coords = new JSONObject();
            try {
                coords.put("latitude", location.getLatitude());
                coords.put("longitude", location.getLongitude());
                coords.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
                coords.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
                coords.put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
                coords.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
                payload.put("ok", true);
                payload.put("coords", coords);
                payload.put("timestamp", location.getTime());
                payload.put("provider", location.getProvider());
            } catch (JSONException ignored) {
                return locationError("serialization_failed", "Could not serialize location.");
            }
            return payload;
        }

        private JSONObject locationError(String code, String message) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("ok", false);
                payload.put("code", code);
                payload.put("message", message == null ? "Location failed." : message);
            } catch (JSONException ignored) {
            }
            return payload;
        }

        private void sendLocationResult(String requestId, JSONObject payload) {
            if (bridge == null || bridge.getWebView() == null) {
                return;
            }
            String script = "window.__guidengNativeLocationResult && window.__guidengNativeLocationResult("
                + JSONObject.quote(requestId)
                + ","
                + payload
                + ")";
            bridge.getWebView().evaluateJavascript(script, null);
        }
    }
}
