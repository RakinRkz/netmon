#!/usr/bin/env bash
# Builds build/NetMon.apk without Gradle. Usage: ./build.sh [--install]
# Needs the minimal toolchain (scripts/setup-toolchain.sh of the android-apk-cli skill,
# or set ANDROID_MIN_TOOLCHAIN to a dir with sdk/build-tools/35.0.0, sdk/platforms/android-35, jdk17).
set -euo pipefail
cd "$(dirname "$0")"
export APK_NAME=NetMon MIN_SDK=26 TARGET_SDK=35
export VERSION_CODE=${VERSION_CODE:-1} VERSION_NAME=${VERSION_NAME:-1.0}
scripts/build-apk.sh .
if [ "${1:-}" = "--install" ]; then
    adb install -r build/NetMon.apk
    adb shell am start -n com.opendroid.netmon/.MainActivity
fi
