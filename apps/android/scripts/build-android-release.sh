#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)"
ANDROID_DIR="$ROOT_DIR/apps/android"
PROPERTIES_PATH="$ANDROID_DIR/keystore.properties"
APK_PATH="$ANDROID_DIR/build/outputs/apk/release/SmsPusher-release.apk"

if [ ! -f "$PROPERTIES_PATH" ]; then
  echo "Missing $PROPERTIES_PATH" >&2
  echo "Run apps/android/scripts/generate-android-release-keystore.sh first." >&2
  exit 1
fi

. "$ROOT_DIR/scripts/dev-env.sh"

(cd "$ANDROID_DIR" && ./gradlew testDebugUnitTest assembleRelease)

if [ ! -f "$APK_PATH" ]; then
  echo "Expected release APK was not created: $APK_PATH" >&2
  exit 1
fi

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  BUILD_TOOLS_VERSION="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d -print | sort | tail -n 1)"
  APKSIGNER="$BUILD_TOOLS_VERSION/apksigner"
  if [ -x "$APKSIGNER" ]; then
    "$APKSIGNER" verify --verbose "$APK_PATH"
  fi
fi

echo "$APK_PATH"
