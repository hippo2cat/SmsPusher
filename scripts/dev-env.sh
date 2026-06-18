#!/usr/bin/env sh

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ ! -d "$ROOT_DIR/apps/android" ]; then
  ROOT_DIR="$(pwd)"
fi

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_HOME="$HOME/gradle-9.5.1"
export GRADLE_USER_HOME="$ROOT_DIR/apps/android/.gradle"
export PATH="$ANDROID_HOME/platform-tools:$GRADLE_HOME/bin:$JAVA_HOME/bin:$PATH"
