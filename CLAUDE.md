# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

NetMon (`com.opendroid.netmon`) is an Internet Speed Meter–style Android app. It shows the live
network speed in the status bar icon and notification, daily mobile/Wi-Fi usage history, per-app
usage, data limit alerts, a floating meter and a Quick Settings tile. It is plain Java on framework
APIs only. There is no Gradle, AndroidX, Kotlin or library code.

## Build, install, verify

```sh
scripts/setup-toolchain.sh   # one-time: JDK 17 + SDK platform 35 + build-tools 35.0.0 (Linux x86_64)
./build.sh                   # → build/NetMon.apk, debug key (wipes build/ first)
./build.sh install           # build + adb install -r
./build.sh run               # build + install + launch MainActivity
KEYSTORE=… KS_PASS=… ./build.sh release   # → build/NetMon-<version>.apk + .sha256, prints cert digest
```

- `build.sh` sets `MIN_SDK=26`, `TARGET_SDK=35` and the version, then calls `scripts/build-apk.sh`.
  The version comes from `VERSION_CODE` / `VERSION_NAME`, with defaults in `build.sh`. The script runs
  aapt2 → javac `--release 8 -encoding UTF-8` → d8 → zipalign → apksigner. It uses the toolchain in
  `~/.local/share/android-min-toolchain`.
- **Releases:**
  - Bump the defaults in `build.sh`. `VERSION_CODE` must go up every release.
  - Add a `CHANGELOG.md` entry.
  - Sign with the maintainer's release key. It is kept outside the repo, and every release must use
    the same key, whose certificate digest is in `README.md`.
  - Tag `vX.Y.Z` and attach the APK and its `.sha256` to the GitHub release.
  - A debug-signed install can't be upgraded in place to a release-signed one. Switching means
    uninstalling, which wipes settings and grants.
- There are no tests and no linter. Verify on a device:
  - crashes: `adb logcat -d -t 500 | grep -E "FATAL|AndroidRuntime: "`
  - service state: `adb shell dumpsys activity services com.opendroid.netmon`
  - notifications (title = live speed, `icon=Icon(typ=BITMAP …)` = speed icon):
    `adb shell dumpsys notification --noredact | grep -A30 pkg=com.opendroid.netmon`
  - compare speeds with the kernel: `adb shell cat /proc/net/dev`
  - screenshot: `adb exec-out screencap -p > shot.png`
- Sampling pauses while the screen is off (unless "Update while screen is off" is on), so the
  notification and logs stand still then. Wake the screen before checking live values.
- The test phone is usually someone's daily phone. Don't `logcat -c`, don't uninstall without asking,
  and don't drive the UI with `adb shell input` while they're using it.
- `docs/screenshots/` holds the README images (540×1170 full-color PNGs). Make sure the floating meter
  is off and no private notification icons are in the status bar.

## How it fits together

- `SpeedMonitor`: process-wide 1 Hz sampler, running while any listener is attached (the service,
  a visible screen, the tile). It sums `TrafficStats.getRx/TxBytes(iface)` over the interfaces of
  networks matching the default `NetworkRequest` (INTERNET + NOT_VPN), so hotspot (local-only network)
  and VPN tunnel traffic isn't counted twice. Below Android 12 it falls back to the device-wide total.
  It keeps process-lifetime totals that feed `SelfTracker` and the notification's live "today".
- `SpeedService`: `specialUse` foreground service. Redraws the notification every tick (small icon
  from `StatusIcon`), drives `OverlayController`, and once a minute syncs today's usage from the
  system and checks data limits. It owns all alert state (`resetAlerts`, `resetWarnings`).
- `UsageRepo`: `NetworkStatsManager` queries (device totals, per day, per app) with `SelfTracker` as
  the fallback without usage access. Blocking; run on `UsageRepo.IO` via `UsageRepo.async`.
- UI: one `MainActivity` with four `Page`s (Dashboard, Usage, Apps, Settings) built in code from `Ui`
  helpers, plus `SpeedGraphView` and `UsageChartView`.

## Constraints that shape the code

- **Java 8 language level, framework APIs only.** No AndroidX, Material Components or Fragments. All UI
  is built in code with no layout XML. Guard any API above 26 with `Build.VERSION.SDK_INT`.
- **Sources contain literal UTF-8 characters** (`↓ ↑ · ⚠ ✓ …`) inside strings; the build passes
  `-encoding UTF-8`.
- **Theme tokens** live in `res/values{,-night}/colors.xml` and follow the system light/dark mode.
  Series colors (download/Wi-Fi blue, upload/mobile orange) were validated for color-blind separation
  and contrast; text stays in ink colors, series colors are only for marks.
- **Edge to edge.** Target 35 forces it on Android 15+. `MainActivity` applies the system-bar insets as
  padding on the content frame and on the bottom nav.
- **The speed notification must stay visible in the status bar:**
  - Its channel is IMPORTANCE_DEFAULT with no sound or vibration; LOW ("silent") can hide the status
    bar icon. Channel importance can't be raised once created; change it with a new channel ID.
  - It has a sort key so Android never auto-groups it. Any other notification must be in an explicit
    group with a summary (as the limit alerts are); ungrouped, Android bundles it with the meter and
    the status bar then shows the app icon instead of the speed.
- **The floating meter hides while NetMon is on screen**, tracked with `onStart`/`onStop` (counted,
  skipping configuration changes), not resume/pause, so dialogs over the app don't bring it back.
