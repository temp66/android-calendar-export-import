#!/usr/bin/env bash
# Provisions a complete toolchain inside tools/, for machines that have no JDK or Android SDK:
# CI runners and containers. A normal workstation does not need this - install JDK 17 and the
# Android SDK, then run ./gradlew.
#
# Where the toolchain lives is described by scripts/env.sh, which is tracked and locates itself,
# so this script never writes a path down and moving the repository changes nothing.
#
#   tools/jdk                  JDK 17 (unpacked from Ubuntu packages)
#   tools/gradle-home          Gradle's user home, kept out of $HOME
#   tools/android-sdk          cmdline-tools, platform-tools, platform 36, build-tools 36.0.0
#
# The network can truncate large transfers, so every download is resumed in a loop until the
# byte count matches the server's Content-Length.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DL="$ROOT/work/dl"
TOOLS="$ROOT/tools"
UBUNTU=https://archive.ubuntu.com/ubuntu/pool/main/o/openjdk-17
mkdir -p "$DL" "$TOOLS"

# A truncated download is only detectable by reading the archive: some mirrors send no
# Content-Length at all, and dpkg-deb -I checks only the control member, which stays intact even
# when the payload is cut short. So every candidate is read in full instead.
archive_ok() { # file
  case "$1" in
    *.deb) dpkg-deb --fsys-tarfile "$1" 2>/dev/null | tar -tf - >/dev/null 2>&1 ;;
    *.zip) unzip -tq "$1" >/dev/null 2>&1 ;;
    *) return 1 ;;
  esac
}

fetch() { # url out
  local url="$1" out="$2" want size i
  want=$(curl -sSIL --max-time 40 "$url" | tr -d '\r' |
    awk 'BEGIN{IGNORECASE=1}/^content-length:/{v=$2} END{print v}')
  echo "[fetch] $(basename "$out") expected=${want:-not advertised}"
  for i in $(seq 1 40); do
    if archive_ok "$out"; then
      echo "[fetch] $(basename "$out") complete ($(stat -c%s "$out") bytes)"
      return 0
    fi
    curl -sS -L -C - --retry 2 --retry-delay 1 --retry-all-errors \
      --connect-timeout 20 --max-time 150 -o "$out" "$url" >/dev/null 2>&1
    size=$(stat -c%s "$out" 2>/dev/null || echo 0)
    echo "[fetch] $(basename "$out") attempt $i: $size bytes"
  done
  echo "[fetch] $(basename "$out") could not be completed after 40 attempts" >&2
  return 1
}

echo "[bootstrap] downloading"
fetch "$UBUNTU/openjdk-17-jre-headless_17.0.20+8-1~24.04_amd64.deb" "$DL/jre.deb" || exit 1
fetch "$UBUNTU/openjdk-17-jdk-headless_17.0.20+8-1~24.04_amd64.deb" "$DL/jdk.deb" || exit 1
fetch "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
  "$DL/cmdline-tools.zip" || exit 1

echo "[bootstrap] unpacking"
rm -rf "$TOOLS/jdk-raw" "$TOOLS/jdk" && mkdir -p "$TOOLS/jdk-raw"
dpkg-deb -x "$DL/jre.deb" "$TOOLS/jdk-raw"
dpkg-deb -x "$DL/jdk.deb" "$TOOLS/jdk-raw"
mv "$TOOLS/jdk-raw/usr/lib/jvm/java-17-openjdk-amd64" "$TOOLS/jdk"
# The Ubuntu packages symlink their configuration into /etc, which does not exist under this
# prefix; replace those symlinks with the packaged copies.
mkdir -p "$TOOLS/jdk/etc"
cp -r "$TOOLS/jdk-raw/etc/." "$TOOLS/jdk/etc/" 2>/dev/null || true
while IFS= read -r link; do
  target=$(readlink "$link")
  case "$target" in
    /etc/*) source="$TOOLS/jdk$target"
            if [ -e "$source" ]; then rm "$link"; cp "$source" "$link"; fi ;;
  esac
done < <(find "$TOOLS/jdk" -type l)
rm -rf "$TOOLS/jdk-raw"

rm -rf "$TOOLS/android-sdk"
mkdir -p "$TOOLS/android-sdk/cmdline-tools"
unzip -q -o "$DL/cmdline-tools.zip" -d "$TOOLS/android-sdk/cmdline-tools/tmp"
mv "$TOOLS/android-sdk/cmdline-tools/tmp/cmdline-tools" "$TOOLS/android-sdk/cmdline-tools/latest"
rm -rf "$TOOLS/android-sdk/cmdline-tools/tmp"

mkdir -p "$TOOLS/gradle-home" "$TOOLS/android-user"

# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"
"$ROOT/scripts/make-truststore.sh"

echo "[bootstrap] installing SDK packages"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-36" \
  "build-tools;36.0.0" >/dev/null

echo "[bootstrap] done"
"$JAVA_HOME/bin/java" -version
echo "now run: ./gradlew test assembleDebug   (or scripts/build.sh)"
