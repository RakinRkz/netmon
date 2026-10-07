package com.opendroid.netmon;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide 1 Hz sampler of the device's traffic counters. It runs only while at least one
 * listener (the service, a visible screen, the Quick Settings tile) is attached; all callbacks
 * happen on the main thread.
 */
final class SpeedMonitor {
    interface Listener {
        void onTick(SpeedMonitor m);
    }

    static final int HISTORY = 60;
    private static final long INTERVAL_MS = 1000;

    private static SpeedMonitor instance;

    static synchronized SpeedMonitor get(Context c) {
        if (instance == null) instance = new SpeedMonitor(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final ConnectivityManager cm;
    private boolean running;
    private long nextTickAt;

    private long lastTime, lastRx, lastTx;
    private long stoppedAt;

    /** Current speeds in bytes per second. */
    private double down, up;
    private final float[] histDown = new float[HISTORY];
    private final float[] histUp = new float[HISTORY];
    private int histCount;

    private long sessionStart, sessionRx, sessionTx;
    private double peakDown, peakUp;
    private volatile String networkName = "Offline";
    private volatile Network defaultNetwork;

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            defaultNetwork = network;
            networkName = describe(caps);
        }

        @Override
        public void onLost(Network network) {
            // A late onLost for the previous default must not mask the new one.
            if (network.equals(defaultNetwork)) networkName = "Offline";
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            sample();
            nextTickAt += INTERVAL_MS;
            long now = SystemClock.uptimeMillis();
            if (nextTickAt <= now) nextTickAt = now + INTERVAL_MS;  // fell behind (e.g. deep sleep)
            handler.postAtTime(this, nextTickAt);
        }
    };

    private SpeedMonitor(Context app) {
        this.app = app;
        cm = app.getSystemService(ConnectivityManager.class);
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        if (!running) start();
    }

    void removeListener(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty() && running) stop();
    }

    boolean isRunning() { return running; }

    private void start() {
        running = true;
        long now = SystemClock.elapsedRealtime();
        // A long pause (screen off) makes the old graph meaningless; start it fresh.
        if (stoppedAt == 0 || now - stoppedAt > HISTORY * INTERVAL_MS) {
            histCount = 0;
        }
        if (sessionStart == 0) resetSession();
        lastTime = now;
        lastRx = rx();
        lastTx = tx();
        down = up = 0;
        try {
            networkName = describe(cm.getNetworkCapabilities(cm.getActiveNetwork()));
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        nextTickAt = SystemClock.uptimeMillis() + INTERVAL_MS;
        handler.postAtTime(tick, nextTickAt);
    }

    private void stop() {
        running = false;
        stoppedAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        try {
            cm.unregisterNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        SelfTracker.flush(app);
    }

    void resetSession() {
        sessionStart = System.currentTimeMillis();
        sessionRx = sessionTx = 0;
        peakDown = peakUp = 0;
    }

    private void sample() {
        long now = SystemClock.elapsedRealtime();
        long rx = rx(), tx = tx();
        long dt = Math.max(1, now - lastTime);
        // Counters can go backwards when an interface is torn down; treat that as no traffic.
        long drx = Math.max(0, rx - lastRx);
        long dtx = Math.max(0, tx - lastTx);
        down = drx * 1000.0 / dt;
        up = dtx * 1000.0 / dt;
        lastTime = now;
        lastRx = rx;
        lastTx = tx;

        sessionRx += drx;
        sessionTx += dtx;
        peakDown = Math.max(peakDown, down);
        peakUp = Math.max(peakUp, up);

        if (histCount < HISTORY) {
            histDown[histCount] = (float) down;
            histUp[histCount] = (float) up;
            histCount++;
        } else {
            System.arraycopy(histDown, 1, histDown, 0, HISTORY - 1);
            System.arraycopy(histUp, 1, histUp, 0, HISTORY - 1);
            histDown[HISTORY - 1] = (float) down;
            histUp[HISTORY - 1] = (float) up;
        }

        SelfTracker.onCounters(app, rx, tx, mobileRx(), mobileTx());

        for (Listener l : new ArrayList<>(listeners)) l.onTick(this);
    }

    double down() { return down; }
    double up() { return up; }
    double peakDown() { return peakDown; }
    double peakUp() { return peakUp; }
    long sessionRx() { return sessionRx; }
    long sessionTx() { return sessionTx; }
    long sessionStart() { return sessionStart; }
    String networkName() { return networkName; }

    /** Copies the history (oldest first) into the given arrays; returns the number of samples. */
    int history(float[] downOut, float[] upOut) {
        System.arraycopy(histDown, 0, downOut, 0, histCount);
        System.arraycopy(histUp, 0, upOut, 0, histCount);
        return histCount;
    }

    static long rx() { return Math.max(0, TrafficStats.getTotalRxBytes()); }
    static long tx() { return Math.max(0, TrafficStats.getTotalTxBytes()); }
    static long mobileRx() { return Math.max(0, TrafficStats.getMobileRxBytes()); }
    static long mobileTx() { return Math.max(0, TrafficStats.getMobileTxBytes()); }

    private static String describe(NetworkCapabilities caps) {
        if (caps == null) return "Offline";
        String base;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) base = "Wi-Fi";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) base = "Mobile data";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) base = "Ethernet";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) base = "Bluetooth";
        else base = "Connected";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) base += " · VPN";
        return base;
    }
}
