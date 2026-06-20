#!/usr/bin/env bash
# Provision a machine to build and test the full QuestLoop project (:core AND
# :app) without relying on CI. Installs graphify (the codebase knowledge-graph
# tool — https://github.com/safishamsi/graphify), JDK 17 (the toolchain AGP
# targets) and a minimal Android SDK (cmdline-tools, platform-tools, the
# compileSdk platform and matching build-tools). Idempotent: safe to re-run;
# skips work already done.
#
# Used two ways:
#   • Claude Code on the web / cloud setup script: call this from the env's setup
#     script (and set JAVA_HOME / ANDROID_HOME in the env-vars panel — see below).
#   • Local dev: run it once, then export the vars it prints.
#
# After running, set (the cloud env-vars panel persists these for the session):
#   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
#   ANDROID_HOME=$HOME/android-sdk
#   ANDROID_SDK_ROOT=$HOME/android-sdk
#
# NOTE: the emulator (`:app:connectedDebugAndroidTest`) is intentionally NOT
# installed — it needs hardware virtualization (/dev/kvm), which these containers
# don't expose. Instrumented UI/migration tests stay on CI (the [uitest] trigger).
set -euo pipefail

# Versions the build needs. Keep in sync with app/build.gradle.kts (compileSdk)
# and gradle/libs.versions.toml (agp).
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-36}"   # compileSdk 36 (Android 16)
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# sudo only if we aren't already root and it's available.
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  command -v sudo >/dev/null 2>&1 && SUDO="sudo" || {
    echo "ERROR: need root or sudo to install JDK 17." >&2; exit 1; }
fi

echo "==> [1/4] graphify (codebase knowledge graph; https://github.com/safishamsi/graphify)"
if ! command -v graphify >/dev/null 2>&1; then
  curl -LSsf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.local/bin:$PATH"
  uv tool install graphifyy   # PyPI package is 'graphifyy' (double-y); CLI is 'graphify'
fi

echo "==> [2/4] JDK 17 (AGP targets 17; many containers ship only a newer JDK)"
if [ ! -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]; then
  $SUDO apt-get update -qq
  # `env VAR=val` (not a bare `VAR=val` prefix): when run as root $SUDO is empty,
  # and a bare assignment after an empty expansion is parsed as the command name.
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    openjdk-17-jdk-headless unzip curl
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
"$JAVA_HOME/bin/java" -version

echo "==> [3/4] Android command-line tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o /tmp/cmdtools.zip \
    "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
  unzip -q /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f /tmp/cmdtools.zip
fi
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "==> [4/4] SDK packages: platform-tools, platforms;$ANDROID_PLATFORM, build-tools;$ANDROID_BUILD_TOOLS"
# `yes |` accepts licenses; `|| true` because yes() exits non-zero on SIGPIPE.
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --install \
  "platform-tools" \
  "platforms;$ANDROID_PLATFORM" \
  "build-tools;$ANDROID_BUILD_TOOLS" >/dev/null

cat <<EOF

✅ Done. Build/test the whole project locally with:
   export JAVA_HOME=$JAVA_HOME
   export ANDROID_HOME=$ANDROID_HOME
   export ANDROID_SDK_ROOT=$ANDROID_HOME
   ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
EOF
