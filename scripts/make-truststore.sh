#!/usr/bin/env bash
# Builds tools/jdk/lib/security/cacerts from the system CA bundle.
#
# The JDK here comes from unpacked Ubuntu packages, so its default trust store lookup finds
# nothing and every HTTPS connection fails with "the trustAnchors parameter must be non-empty".
# Rebuilding the store from the system bundle fixes it; scripts/build.sh then names the file
# explicitly through JAVA_TOOL_OPTIONS.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="$ROOT/tools/jdk"
BUNDLE="${CA_BUNDLE:-/etc/ssl/certs/ca-certificates.crt}"
STORE="$JAVA_HOME/lib/security/cacerts"
WORK="$ROOT/work/ca"

[ -x "$JAVA_HOME/bin/keytool" ] || { echo "JDK missing; run bootstrap-toolchain.sh first" >&2; exit 1; }
[ -f "$BUNDLE" ] || { echo "CA bundle $BUNDLE not found" >&2; exit 1; }

rm -rf "$WORK" && mkdir -p "$WORK"
awk 'BEGIN{n=0} /BEGIN CERTIFICATE/{n++} n>0{print > sprintf("'"$WORK"'/cert%03d.pem", n)}' "$BUNDLE"
rm -f "$STORE"
for pem in "$WORK"/cert*.pem; do
  "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias "$(basename "$pem" .pem)" \
    -file "$pem" -keystore "$STORE" -storepass changeit >/dev/null 2>&1 || true
done
count=$("$JAVA_HOME/bin/keytool" -list -keystore "$STORE" -storepass changeit 2>/dev/null |
  grep -c "trustedCertEntry" || true)
echo "[truststore] $count certificates imported into $STORE"
