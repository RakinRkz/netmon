package com.opendroid.netmon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.service.quicksettings.TileService;

/**
 * Foreground service behind the status bar meter: keeps the sampler running, redraws the
 * notification (whose small icon is the speed itself) every second, drives the floating meter and
 * checks the mobile data limits once a minute. Sampling pauses while the screen is off.
 */
public class SpeedService extends Service implements SpeedMonitor.Listener {
    static final String CH_SPEED = "speed";
    static final String CH_ALERTS = "alerts";
    private static final int NOTIF_ID = 1;
    private static final int ALERT_CYCLE_ID = 2;
    private static final int ALERT_DAY_ID = 3;
    private static final int ALERT_SUMMARY_ID = 4;
    // Alerts live in their own explicit group (with a summary). Otherwise Android auto-groups them
    // with the meter, and the status bar then shows the group's app icon instead of the speed.
    private static final String GROUP_ALERTS = "data_alerts";
    private static final long SYNC_MS = 60_000;
    private static final long MB = 1024L * 1024;

    private static SpeedService instance;
    private static boolean appVisible;

    private NotificationManager nm;
    private Notification.Builder builder;
    private SpeedMonitor monitor;
    private OverlayController overlay;
    private boolean listening;

    // Today's usage = the system's figure at the last sync + counter deltas since then,
    // so the notification moves every second without querying the system every second.
    private UsageRepo.Usage todayBase;
    private long baseDay, baseRx, baseTx, baseMRx, baseMTx;
    private long lastSync;
    private boolean syncing;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (!Prefs.screenOffUpdates(c)) pause();
            } else {
                resume();
            }
        }
    };

    static boolean isRunning() { return instance != null; }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SpeedService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, SpeedService.class));
    }

    /** Re-applies settings (units, overlay, lock screen...) to a running service. */
    static void refresh() {
        if (instance != null) instance.applySettings();
    }

    /**
     * Forgets the chosen alerts (monthly cycle and/or daily) and removes them if posted, so they
     * fire afresh, e.g. after that limit or the billing day changed. The other kind is untouched.
     */
    static void resetAlerts(Context c, boolean cycle, boolean day) {
        SharedPreferences.Editor e = Prefs.get(c).edit();
        if (cycle) e.remove(Prefs.ALERTED_CYCLE);
        if (day) e.remove(Prefs.ALERTED_DAY);
        e.apply();
        cancelAlerts(c, cycle, day);
    }

    /**
     * After the warning threshold changed: forgets warnings so they fire at the new threshold.
     * "Limit reached" alerts stay, as the limits themselves didn't change.
     */
    static void resetWarnings(Context c) {
        SharedPreferences p = Prefs.get(c);
        resetAlerts(c, alertedLevel(p.getString(Prefs.ALERTED_CYCLE, "")) == 1,
                alertedLevel(p.getString(Prefs.ALERTED_DAY, "")) == 1);
    }

    /** The level in an ALERTED_* marker "periodStart:level": 1 warned, 2 limit reached, 0 none. */
    private static int alertedLevel(String marker) {
        int i = marker.indexOf(':');
        return i < 0 ? 0 : Integer.parseInt(marker.substring(i + 1));
    }

    private static void cancelAlerts(Context c, boolean cycle, boolean day) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        // Cancelling the summary also removes its group's alerts, so keep it while another alert
        // remains. Check before cancelling: cancel() is applied asynchronously.
        boolean othersLeft = false;
        for (StatusBarNotification n : nm.getActiveNotifications()) {
            if ((n.getId() == ALERT_CYCLE_ID && !cycle) || (n.getId() == ALERT_DAY_ID && !day)) othersLeft = true;
        }
        if (cycle) nm.cancel(ALERT_CYCLE_ID);
        if (day) nm.cancel(ALERT_DAY_ID);
        if (!othersLeft) nm.cancel(ALERT_SUMMARY_ID);
    }

    /**
     * The floating meter steps aside while NetMon itself is on screen. Runs on every app
     * start/stop, so it touches only the overlay.
     */
    static void setAppVisible(boolean visible) {
        appVisible = visible;
        if (instance == null) return;
        instance.updateOverlay();
        // Usage access may have just been granted or revoked in Settings: switch the
        // notification's totals over now rather than at the next minutely sync.
        if (visible && (instance.todayBase != null) != UsageRepo.hasUsageAccess(instance)) instance.lastSync = 0;
    }

    /** Re-creates the floating meter, e.g. after its saved position was reset. */
    static void restartOverlay() {
        if (instance != null) {
            instance.overlay.hide();
            instance.updateOverlay();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = getSystemService(NotificationManager.class);
        createChannels(this);
        monitor = SpeedMonitor.get(this);
        overlay = new OverlayController(this);

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        builder = new Notification.Builder(this, CH_SPEED)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                // Android only auto-groups notifications with neither a group nor a sort key
                // (StatusBarNotification.isAppGroup()); bundled, the status bar shows the app icon, not
                // the speed. A sort key opts out; a summary-less setGroup() can be force-grouped on 15+.
                .setSortKey("speed_meter")
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);

        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isInteractive() || Prefs.screenOffUpdates(this)) resume();
        applySettings();
        sync();
        updateTile();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        pause();
        overlay.hide();
        SelfTracker.flush(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        updateTile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pause() {
        if (listening) {
            monitor.removeListener(this);
            listening = false;
        }
    }

    private void resume() {
        if (!listening) {
            monitor.addListener(this);
            listening = true;
        }
    }

    private void applySettings() {
        updateOverlay();
        nm.notify(NOTIF_ID, buildNotification());
        lastSync = 0;  // limits may have changed; re-check on the next tick
    }

    /** Adds or removes the floating meter per its setting; while NetMon is on screen it's only hidden. */
    private void updateOverlay() {
        // The floating meter is only "on" while its permission is held; if the permission was
        // revoked, turn the setting off too so it can't silently reappear when re-granted.
        if (Prefs.overlay(this) && !OverlayController.permitted(this)) Prefs.put(this, Prefs.OVERLAY, false);
        overlay.setHidden(appVisible);
        if (Prefs.overlay(this)) overlay.show();
        else overlay.hide();
        overlay.update(monitor.down(), monitor.up(), Prefs.useBits(this));
    }

    @Override
    public void onTick(SpeedMonitor m) {
        long now = System.currentTimeMillis();
        if (now - lastSync > SYNC_MS || UsageRepo.startOfDay(now) != baseDay) sync();
        nm.notify(NOTIF_ID, buildNotification());
        overlay.update(m.down(), m.up(), Prefs.useBits(this));
    }

    private Notification buildNotification() {
        boolean bits = Prefs.useBits(this);
        double down = monitor.down(), up = monitor.up();
        int mode = Prefs.iconMode(this);
        double iconSpeed = mode == Prefs.ICON_DOWNLOAD ? down : mode == Prefs.ICON_UPLOAD ? up : down + up;
        UsageRepo.Usage t = today();
        builder.setSmallIcon(Prefs.speedIcon(this)
                        ? StatusIcon.make(iconSpeed, bits)
                        : Icon.createWithResource(this, R.drawable.ic_speed))
                .setContentTitle("↓ " + Fmt.speed(down, bits) + "    ↑ " + Fmt.speed(up, bits))
                .setContentText("Today  ·  Mobile " + Fmt.bytes(t.mobile()) + "  ·  Wi-Fi " + Fmt.bytes(t.wifi()))
                .setSubText(monitor.networkName())
                .setVisibility(Prefs.lockScreen(this) ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_SECRET);
        return builder.build();
    }

    /** Today's usage, live to the second. */
    private UsageRepo.Usage today() {
        long now = System.currentTimeMillis();
        if (todayBase == null || UsageRepo.startOfDay(now) != baseDay) {
            long[] d = SelfTracker.day(this, UsageRepo.startOfDay(now));
            UsageRepo.Usage u = new UsageRepo.Usage();
            u.mobileRx = d[0];
            u.mobileTx = d[1];
            u.wifiRx = d[2];
            u.wifiTx = d[3];
            return u;
        }
        UsageRepo.Usage u = new UsageRepo.Usage();
        u.add(todayBase);
        long dmrx = Math.max(0, SpeedMonitor.mobileRx() - baseMRx);
        long dmtx = Math.max(0, SpeedMonitor.mobileTx() - baseMTx);
        u.mobileRx += dmrx;
        u.mobileTx += dmtx;
        u.wifiRx += Math.max(0, SpeedMonitor.rx() - baseRx - dmrx);
        u.wifiTx += Math.max(0, SpeedMonitor.tx() - baseTx - dmtx);
        return u;
    }

    /** Refreshes today's system figure and checks the data limits, off the main thread. */
    private void sync() {
        if (syncing) return;
        syncing = true;
        lastSync = System.currentTimeMillis();
        final Context c = getApplicationContext();
        final long rx = SpeedMonitor.rx(), tx = SpeedMonitor.tx();
        final long mrx = SpeedMonitor.mobileRx(), mtx = SpeedMonitor.mobileTx();
        final long day = UsageRepo.startOfDay(lastSync);
        final long cycleStart = UsageRepo.cycleStart(lastSync, Prefs.cycleDay(c));
        UsageRepo.async(() -> {
            long now = System.currentTimeMillis();
            UsageRepo.Usage[] r = new UsageRepo.Usage[2];
            if (UsageRepo.hasUsageAccess(c)) r[0] = UsageRepo.query(c, day, now);
            if (Prefs.monthlyLimitMb(c) > 0) r[1] = UsageRepo.query(c, cycleStart, now);
            return r;
        }, r -> {
            syncing = false;
            if (instance != this || r == null) return;
            if (r[0] != null) {
                todayBase = r[0];
                baseDay = day;
                baseRx = rx;
                baseTx = tx;
                baseMRx = mrx;
                baseMTx = mtx;
            } else {
                todayBase = null;
            }
            checkLimits(cycleStart, r[1], today().mobile());
        });
    }

    /** Alerts on the limits; {@code cycle} is the mobile usage since {@code cycleStart}, as queried. */
    private void checkLimits(long cycleStart, UsageRepo.Usage cycle, long todayMobile) {
        int warn = Prefs.warnPct(this);
        long monthly = Prefs.monthlyLimitMb(this) * MB;
        if (monthly > 0 && cycle != null) {
            if (cycleStart != UsageRepo.cycleStart(System.currentTimeMillis(), Prefs.cycleDay(this))) {
                // The billing day changed (or a new cycle began) during the query: this usage
                // belongs to another cycle, so don't record it against the current one. Re-sync.
                lastSync = 0;
            } else {
                alertIfNeeded(Prefs.ALERTED_CYCLE, cycleStart, cycle.mobile(), monthly, warn, ALERT_CYCLE_ID,
                        "this billing cycle (since " + Fmt.shortDate(cycleStart) + ")", "Monthly");
            }
        }
        long daily = Prefs.dailyLimitMb(this) * MB;
        if (daily > 0) {
            alertIfNeeded(Prefs.ALERTED_DAY, UsageRepo.startOfDay(System.currentTimeMillis()), todayMobile, daily,
                    warn, ALERT_DAY_ID, "today", "Daily");
        }
    }

    /** Posts at most one warning and one limit-reached alert per period. */
    private void alertIfNeeded(String key, long period, long used, long limit, int warnPct, int id,
                               String when, String kind) {
        int level = used >= limit ? 2 : used * 100 >= limit * warnPct ? 1 : 0;
        if (level == 0) return;
        String stored = Prefs.get(this).getString(key, "");
        int alerted = stored.startsWith(period + ":") ? alertedLevel(stored) : 0;
        if (level <= alerted) return;
        Prefs.put(this, key, period + ":" + level);

        String title = level == 2
                ? kind + " mobile data limit reached"
                : kind + " mobile data: " + (used * 100 / limit) + "% used";
        String text = Fmt.bytes(used) + " of " + Fmt.bytes(limit) + " used " + when + ".";
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setGroup(GROUP_ALERTS)
                .setContentIntent(PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_IMMUTABLE))
                .build();
        nm.notify(id, n);
        nm.notify(ALERT_SUMMARY_ID, new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle("Mobile data alerts")
                .setGroup(GROUP_ALERTS)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .build());
    }

    private void updateTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, SpeedTileService.class));
        } catch (RuntimeException ignored) {
        }
    }

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        // Default (not low) importance: some launchers hide "silent" notifications from the status
        // bar, which would hide the meter itself. No sound or vibration, and updates never re-alert.
        NotificationChannel speed = new NotificationChannel(CH_SPEED, "Speed meter", NotificationManager.IMPORTANCE_DEFAULT);
        speed.setDescription("Live network speed in the status bar");
        speed.setSound(null, null);
        speed.enableVibration(false);
        speed.enableLights(false);
        speed.setShowBadge(false);
        nm.createNotificationChannel(speed);

        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Data limit alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Warnings when mobile data use nears or passes your limit");
        nm.createNotificationChannel(alerts);
    }
}
