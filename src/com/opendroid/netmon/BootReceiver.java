package com.opendroid.netmon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the meter after a reboot (if "Start on boot" is on) and after the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !updated) return;
        if (!Prefs.enabled(c) || (boot && !Prefs.startOnBoot(c))) return;
        try {
            SpeedService.start(c);
        } catch (RuntimeException ignored) {
            // Start refused; the meter comes back the next time the app is opened.
        }
    }
}
