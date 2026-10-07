package com.opendroid.netmon;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: turns the speed meter on and off, and shows the live speed while the shade is open. */
public class SpeedTileService extends TileService implements SpeedMonitor.Listener {
    private boolean listening;

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (SpeedService.isRunning()) {
            SpeedMonitor.get(this).addListener(this);
            listening = true;
        }
        updateTile();
    }

    @Override
    public void onStopListening() {
        stopTicks();
        super.onStopListening();
    }

    private void stopTicks() {
        if (listening) {
            SpeedMonitor.get(this).removeListener(this);
            listening = false;
        }
    }

    @Override
    public void onClick() {
        if (SpeedService.isRunning()) {
            Prefs.put(this, Prefs.ENABLED, false);
            stopTicks();
            SpeedService.stop(this);
            showState(false);
        } else {
            Prefs.put(this, Prefs.ENABLED, true);
            try {
                SpeedService.start(this);
                showState(true);
            } catch (RuntimeException e) {
                // Background start refused (e.g. battery restrictions): open the app, which starts it.
                Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
                } else {
                    startActivityAndCollapse(open);
                }
            }
        }
    }

    @Override
    public void onTick(SpeedMonitor m) {
        updateTile();
    }

    private void updateTile() {
        showState(SpeedService.isRunning());
    }

    private void showState(boolean on) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Speed meter");
        if (Build.VERSION.SDK_INT >= 29) {
            SpeedMonitor m = SpeedMonitor.get(this);
            boolean bits = Prefs.useBits(this);
            tile.setSubtitle(on
                    ? (m.isRunning() ? "↓" + Fmt.speed(m.down(), bits) + "  ↑" + Fmt.speed(m.up(), bits) : "On")
                    : "Off");
        }
        tile.updateTile();
    }
}
