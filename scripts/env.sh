#!/usr/bin/env bash
# Environment for the repo-local toolchain, if scripts/bootstrap-toolchain.sh has provisioned one.
#
# This file is tracked on purpose. It computes its own location instead of recording one, so the
# repository can be moved or renamed without editing anything, and it stays the single place that
# knows where the provisioned JDK, Gradle home and Android SDK live.
#
# Nothing here overrides an environment that is already set, and a path is exported only when the
# toolchain is actually present, so a developer with their own JDK and SDK is left alone.
#
#   . scripts/env.sh     # in a shell
#   scripts/build.sh     # sources it for you

_env_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"

if [ -d "$_env_root/tools/jdk" ]; then
  export JAVA_HOME="${JAVA_HOME:-$_env_root/tools/jdk}"
fi

# The bootstrapped JDK is an unpacked Ubuntu package and its default trust-store lookup finds
# nothing, so every HTTPS fetch fails with "the trustAnchors parameter must be non-empty". A JDK
# installed the normal way needs none of this, hence the check that we are on the local one.
if [ -n "${JAVA_HOME:-}" ] && [ "$JAVA_HOME" = "$_env_root/tools/jdk" ]; then
  export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$JAVA_HOME/lib/security/cacerts"
  export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStorePassword=changeit"
fi

if [ -d "$_env_root/tools/gradle-home" ]; then
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$_env_root/tools/gradle-home}"
fi

if [ -d "$_env_root/tools/android-sdk" ]; then
  export ANDROID_HOME="${ANDROID_HOME:-$_env_root/tools/android-sdk}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$_env_root/tools/android-user}"
fi

if [ -n "${JAVA_HOME:-}" ]; then
  case ":$PATH:" in
    *":$JAVA_HOME/bin:"*) ;;
    *) PATH="$JAVA_HOME/bin:$PATH" ;;
  esac
  export PATH
fi

unset _env_root
