package com.opendroid.netmon;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-wide 1 Hz sampler of the device's internet traffic. It runs only while at least one
 * listener (the service, a visible screen, the Quick Settings tile) is attached; all callbacks
 * happen on the main thread.
 *
 * Only the interfaces of internet networks are counted (mobile, Wi-Fi client, Ethernet), not the
 * device-wide total: that also counts the hotspot side of tethered traffic (each byte twice, once
 * per interface) and VPN tunnels on top of the network carrying them.
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

    private long lastTime;
    private long stoppedAt;

    // Interface of each internet network. The default request asks for INTERNET and NOT_VPN, which
    // leaves out VPNs and local-only networks such as the hotspot. Registered for the life of the
    // process so the set is current whenever sampling resumes.
    private final Map<Network, String> upstream = new HashMap<>();
    private final Map<String, long[]> lastIface = new HashMap<>();
    private long lastTotalRx = -1, lastTotalTx = -1, lastMobileRx = -1, lastMobileTx = -1;
    /** Running totals since the process started: internet traffic, and its mobile share. */
    private long cumRx, cumTx, cumMobileRx, cumMobileTx;

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

    private final ConnectivityManager.NetworkCallback upstreamCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
            if (lp.getInterfaceName() != null) upstream.put(network, lp.getInterfaceName());
            else upstream.remove(network);
        }

        @Override
        public void onLost(Network network) {
            upstream.remove(network);
        }
    };

    private SpeedMonitor(Context app) {
        this.app = app;
        cm = app.getSystemService(ConnectivityManager.class);
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), upstreamCallback, handler);
        } catch (RuntimeException ignored) {
        }
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
        // Traffic while paused goes into the totals but not into the speed or the session.
        collect();
        lastTime = now;
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
        long[] d = collect();
        long drx = d[0], dtx = d[1];
        long dt = Math.max(1, now - lastTime);
        down = drx * 1000.0 / dt;
        up = dtx * 1000.0 / dt;
        lastTime = now;

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

    long cumRx() { return cumRx; }
    long cumTx() { return cumTx; }
    long cumMobileRx() { return cumMobileRx; }
    long cumMobileTx() { return cumMobileTx; }

    /**
     * Reads the counters and returns the internet traffic {rx, tx} since the last read, adding it
     * (and its mobile and Wi-Fi shares) to the running totals and the self-tracked history.
     * Counters can go backwards when an interface is torn down; that counts as no traffic.
     */
    private long[] collect() {
        long drx = 0, dtx = 0;
        if (Build.VERSION.SDK_INT >= 31) {
            Set<String> seen = new HashSet<>();
            for (String iface : upstream.values()) {
                if (!seen.add(iface)) continue;
                long rx = Math.max(0, TrafficStats.getRxBytes(iface)), tx = Math.max(0, TrafficStats.getTxBytes(iface));
                long[] last = lastIface.get(iface);
                if (last != null) {
                    drx += Math.max(0, rx - last[0]);
                    dtx += Math.max(0, tx - last[1]);
                }
                lastIface.put(iface, new long[] {rx, tx});
            }
            // Forget interfaces that stopped being internet ones: the same name can come back as
            // the hotspot (e.g. wlan0), and its counters then must not count when it returns.
            lastIface.keySet().retainAll(seen);
        } else {
            // No per-interface counters before Android 12: fall back to the device-wide total.
            long rx = Math.max(0, TrafficStats.getTotalRxBytes()), tx = Math.max(0, TrafficStats.getTotalTxBytes());
            if (lastTotalRx >= 0) {
                drx = Math.max(0, rx - lastTotalRx);
                dtx = Math.max(0, tx - lastTotalTx);
            }
            lastTotalRx = rx;
            lastTotalTx = tx;
        }
        long mrx = Math.max(0, TrafficStats.getMobileRxBytes()), mtx = Math.max(0, TrafficStats.getMobileTxBytes());
        long dmrx = lastMobileRx < 0 ? 0 : Math.max(0, mrx - lastMobileRx);
        long dmtx = lastMobileTx < 0 ? 0 : Math.max(0, mtx - lastMobileTx);
        lastMobileRx = mrx;
        lastMobileTx = mtx;
        // Mobile traffic is internet traffic; this also covers the moment before the network
        // callback has reported the mobile interface.
        drx = Math.max(drx, dmrx);
        dtx = Math.max(dtx, dmtx);

        cumRx += drx;
        cumTx += dtx;
        cumMobileRx += dmrx;
        cumMobileTx += dmtx;
        SelfTracker.add(app, dmrx, dmtx, drx - dmrx, dtx - dmtx);
        return new long[] {drx, dtx};
    }

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
