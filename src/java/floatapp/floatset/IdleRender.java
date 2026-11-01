package floatapp.floatset;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Lets the map engine drop its continuous follow rendering to 10 FPS while the car is
 * stationary and nobody is interacting. Gestures and camera animations keep 60 FPS;
 * without a fresh location fix the map is treated as moving.
 */
public final class IdleRender implements LocationListener {
    private static final long HOLD_MS = 5000, FIX_MS = 3000;
    private static final float STILL_MPS = 1f;
    private static native void nativeSetIdle(boolean idle);
    private static native void nativeLowerVoicePriority();

    private static volatile long lastWake;
    private static volatile boolean idle;
    private static long lastFix;
    private static float speed = -1;
    private static Location previous;
    private static Handler handler;
    private static LocationManager locations;
    private static IdleRender listener;

    private static final Runnable check = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            boolean still = lastFix > 0 && now - lastFix < FIX_MS && speed >= 0 && speed < STILL_MPS;
            setIdle(still && now - lastWake > HOLD_MS);
            if (handler != null) handler.postDelayed(this, 1000);
        }
    };

    /** Any touch, page change or lifecycle event: render at full rate for the next few seconds. */
    public static void wake() {
        lastWake = SystemClock.uptimeMillis();
        if (idle) setIdle(false);
    }

    static void start(Activity activity) {
        stop();
        handler = new Handler(Looper.getMainLooper());
        listener = new IdleRender();
        locations = (LocationManager) activity.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        try {
            // Passive: reuses the fixes Amap already requests, no extra GPS work.
            locations.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, listener, Looper.getMainLooper());
        } catch (RuntimeException e) {
            locations = null;
        }
        wake();
        handler.postDelayed(check, 1000);
        // Voice threads start lazily; catch them after startup.
        handler.postDelayed(IdleRender::lowerVoicePriority, 8000);
    }

    /** Also called when navigation starts, in case the voice engine created new threads. */
    static void lowerVoicePriority() {
        nativeLowerVoicePriority();
    }

    static void stop() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (locations != null) locations.removeUpdates(listener);
        handler = null; locations = null; listener = null; previous = null;
        lastFix = 0; speed = -1;
        setIdle(false);
    }

    private static synchronized void setIdle(boolean value) {
        if (idle == value) return;
        idle = value;
        nativeSetIdle(value);
    }

    @Override public void onLocationChanged(Location location) {
        long now = SystemClock.uptimeMillis();
        if (location.hasSpeed()) {
            speed = location.getSpeed();
        } else if (previous != null && location.getTime() > previous.getTime()) {
            speed = previous.distanceTo(location) * 1000f / (location.getTime() - previous.getTime());
        } else {
            speed = -1;
        }
        previous = location;
        lastFix = now;
        if (speed >= STILL_MPS) setIdle(false);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
}
