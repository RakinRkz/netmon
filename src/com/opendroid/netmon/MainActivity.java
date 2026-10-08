package com.opendroid.netmon;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Single activity hosting four tabs: Speed, Usage, Apps and Settings. */
public class MainActivity extends Activity {
    static final int TAB_SPEED = 0, TAB_USAGE = 1, TAB_APPS = 2, TAB_SETTINGS = 3;
    private static final String[] TAB_NAMES = {"Speed", "Usage", "Apps", "Settings"};
    private static final int[] TAB_ICONS = {R.drawable.ic_speed, R.drawable.ic_chart, R.drawable.ic_apps, R.drawable.ic_settings};
    private static final int REQ_NOTIFICATIONS = 1;

    private FrameLayout content;
    private final Page[] pages = new Page[4];
    private final TextView[] tabs = new TextView[4];
    private int current = -1;
    private boolean resumed;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Draw behind the system bars (enforced from Android 15 anyway) and pad for them below.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.color(this, R.color.page));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(Ui.hairline(this));
        final LinearLayout nav = buildNav();
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, l, r;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = i.top; bottom = i.bottom; l = i.left; r = i.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                l = insets.getSystemWindowInsetLeft();
                r = insets.getSystemWindowInsetRight();
            }
            content.setPadding(l, top, r, 0);
            nav.setPadding(l, 0, r, bottom);
            return insets;
        });
        setContentView(root);

        select(state != null ? state.getInt("tab", TAB_SPEED) : TAB_SPEED);

        if (Prefs.enabled(this)) startMeter();
        askForNotificationsOnce();
    }

    private LinearLayout buildNav() {
        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.color(this, R.color.surface));
        for (int i = 0; i < 4; i++) {
            final int index = i;
            TextView t = Ui.medium(this, TAB_NAMES[i], 12, R.color.muted);
            t.setGravity(Gravity.CENTER);
            t.setCompoundDrawablesWithIntrinsicBounds(0, TAB_ICONS[i], 0, 0);
            t.setCompoundDrawablePadding(Ui.dp(this, 2));
            t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            t.setOnClickListener(v -> select(index));
            t.setBackground(getDrawable(android.R.drawable.list_selector_background));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return nav;
    }

    void select(int tab) {
        if (tab == current) return;
        if (current >= 0 && resumed) page(current).onHide();
        current = tab;
        content.removeAllViews();
        content.addView(page(tab).view());
        for (int i = 0; i < 4; i++) {
            int c = Ui.color(this, i == tab ? R.color.accent : R.color.muted);
            tabs[i].setTextColor(c);
            tabs[i].getCompoundDrawables()[1].mutate().setTint(c);
        }
        if (resumed) page(tab).onShow();
    }

    private Page page(int i) {
        if (pages[i] == null) {
            switch (i) {
                case TAB_SPEED: pages[i] = new DashboardPage(this); break;
                case TAB_USAGE: pages[i] = new UsagePage(this); break;
                case TAB_APPS: pages[i] = new AppsPage(this); break;
                default: pages[i] = new SettingsPage(this); break;
            }
        }
        return pages[i];
    }

    // Start/stop, not resume/pause: a dialog-style activity on top (a permission or battery prompt)
    // only pauses NetMon, which is still on screen, so the floating meter must stay hidden.
    // Counted, because a second instance (e.g. opened from the notification over a dialog) starts
    // before the first one stops.
    private static int startedCount;

    @Override
    protected void onStart() {
        super.onStart();
        startedCount++;
        SpeedService.setAppVisible(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        page(current).onShow();
    }

    @Override
    protected void onPause() {
        resumed = false;
        page(current).onHide();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // A recreation (dark mode, locale) starts again right away; don't flash the meter in between.
        startedCount--;
        if (startedCount == 0 && !isChangingConfigurations()) SpeedService.setAppVisible(false);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", current);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (current != TAB_SPEED) select(TAB_SPEED);
        else super.onBackPressed();
    }

    // ---- meter ----

    void startMeter() {
        Prefs.put(this, Prefs.ENABLED, true);
        try {
            SpeedService.start(this);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't start the speed meter: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void stopMeter() {
        Prefs.put(this, Prefs.ENABLED, false);
        SpeedService.stop(this);
    }

    // ---- permissions ----

    boolean notificationsAllowed() {
        return getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private void askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed()
                && !Prefs.get(this).getBoolean(Prefs.ASKED_NOTIFICATIONS, false)) {
            Prefs.put(this, Prefs.ASKED_NOTIFICATIONS, true);
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    void fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()), null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        SpeedService.refresh();
        if (resumed) page(current).onShow();
    }

    void openUsageAccess() {
        Toast.makeText(this, "Turn on usage access for NetMon", Toast.LENGTH_LONG).show();
        open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    void openOverlaySettings() {
        // Since Android 11 this always opens the full list, not NetMon's own page.
        Toast.makeText(this, "Find NetMon in the list and allow it", Toast.LENGTH_LONG).show();
        open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
    }

    /** Asks the system to add the Quick Settings tile (Android 13+ shows its own confirmation). */
    void addQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(new ComponentName(this, SpeedTileService.class), "Speed meter",
                Icon.createWithResource(this, R.drawable.ic_tile), getMainExecutor(), result -> {
                    String msg = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ? "Tile added to Quick Settings"
                            : result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ? "The tile is already in Quick Settings"
                            : result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ? null
                            : "Couldn't add the tile; add \"Speed meter\" from the Quick Settings editor";
                    if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                });
    }

    boolean batteryUnrestricted() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    void openBatterySettings() {
        open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    private void open(Intent intent, Intent fallback) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            if (fallback != null) {
                try {
                    startActivity(fallback);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                }
            }
            Toast.makeText(this, "Couldn't open system settings", Toast.LENGTH_SHORT).show();
        }
    }
}
