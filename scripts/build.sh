#!/usr/bin/env bash
# Runs the unit tests, assembles the debug APK and drops it in outputs/.
#
# Uses the Gradle wrapper. If the repo-local toolchain from bootstrap-toolchain.sh is present it
# is used automatically, otherwise JAVA_HOME and the Android SDK must already be set up.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -f "$ROOT/scripts/env.sh" ]; then
  # shellcheck disable=SC1091
  . "$ROOT/scripts/env.sh"
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME is not set. Install JDK 17, or run scripts/bootstrap-toolchain.sh." >&2
  exit 1
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "No Android SDK found. Set ANDROID_HOME/ANDROID_SDK_ROOT, or run" >&2
  echo "scripts/bootstrap-toolchain.sh to provision one under tools/." >&2
  exit 1
fi

./gradlew test assembleDebug "$@"

# AGP names the artifact after the module, so the build tree holds app-debug.apk. The name that
# leaves this script is a distribution concern, and this is where it is decided.
DIST_NAME="android-calendar-export-import"

mkdir -p "$ROOT/outputs"
apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || { echo "expected $apk, but it was not produced" >&2; exit 1; }
cp "$apk" "$ROOT/outputs/$DIST_NAME-debug.apk"
echo "[build] wrote $ROOT/outputs/$DIST_NAME-debug.apk"
