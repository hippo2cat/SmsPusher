#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)"
TAURI_DIR="$ROOT_DIR/apps/tauri"
SRC_TAURI_DIR="$TAURI_DIR/src-tauri"
VERSION_FILE="$ROOT_DIR/version.properties"
APP_NAME="${APP_NAME:-SmsPusher}"
EXECUTABLE_NAME="${EXECUTABLE_NAME:-SmsPusher}"
BUNDLE_IDENTIFIER="${BUNDLE_IDENTIFIER:-com.jbz.smspusher}"
if [ -f "$VERSION_FILE" ]; then
  DESKTOP_VERSION_NAME="$(sed -n 's/^DESKTOP_VERSION_NAME=//p' "$VERSION_FILE" | head -n 1)"
  DESKTOP_BUILD_NUMBER="$(sed -n 's/^DESKTOP_BUILD_NUMBER=//p' "$VERSION_FILE" | head -n 1)"
fi
BUNDLE_VERSION="${BUNDLE_VERSION:-${DESKTOP_BUILD_NUMBER:-1}}"
BUNDLE_SHORT_VERSION="${BUNDLE_SHORT_VERSION:-${DESKTOP_VERSION_NAME:-0.1.0}}"
CONFIGURATION="${CONFIGURATION:-release}"
OUTPUT_DIR="${PACKAGE_OUTPUT_DIR:-$TAURI_DIR/build/package}"
APP_BUNDLE="$OUTPUT_DIR/$APP_NAME.app"
CONTENTS_DIR="$APP_BUNDLE/Contents"
MACOS_BUNDLE_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"
INFO_PLIST="$CONTENTS_DIR/Info.plist"
APP_ICON="$TAURI_DIR/resources/AppIcon.icns"

if [ "$CONFIGURATION" = "release" ]; then
  CARGO_PROFILE_FLAG="--release"
  TARGET_PROFILE_DIR="release"
else
  CARGO_PROFILE_FLAG=""
  TARGET_PROFILE_DIR="debug"
fi

if [ -f "$TAURI_DIR/package.json" ]; then
  (cd "$TAURI_DIR" && npm run build)
fi

cargo build --manifest-path "$SRC_TAURI_DIR/Cargo.toml" $CARGO_PROFILE_FLAG --bin "$EXECUTABLE_NAME"
BUILT_EXECUTABLE="$SRC_TAURI_DIR/target/$TARGET_PROFILE_DIR/$EXECUTABLE_NAME"

if [ ! -x "$BUILT_EXECUTABLE" ]; then
  echo "missing built executable: $BUILT_EXECUTABLE" >&2
  exit 1
fi

case "$APP_BUNDLE" in
  "$TAURI_DIR"/build/package/*.app|"$TAURI_DIR"/build/test-package/*.app|/private/tmp/*.app|/tmp/*.app)
    rm -rf "$APP_BUNDLE"
    ;;
  *)
    echo "refusing to remove app bundle outside known output directories: $APP_BUNDLE" >&2
    exit 1
    ;;
esac

mkdir -p "$MACOS_BUNDLE_DIR" "$RESOURCES_DIR"
cp "$BUILT_EXECUTABLE" "$MACOS_BUNDLE_DIR/$EXECUTABLE_NAME"

if [ ! -f "$APP_ICON" ]; then
  echo "missing app icon: $APP_ICON" >&2
  exit 1
fi
cp "$APP_ICON" "$RESOURCES_DIR/AppIcon.icns"

cat > "$INFO_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>en</string>
  <key>CFBundleDisplayName</key>
  <string>$APP_NAME</string>
  <key>CFBundleExecutable</key>
  <string>$EXECUTABLE_NAME</string>
  <key>CFBundleIconFile</key>
  <string>AppIcon</string>
  <key>CFBundleIdentifier</key>
  <string>$BUNDLE_IDENTIFIER</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>$APP_NAME</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>$BUNDLE_SHORT_VERSION</string>
  <key>CFBundleVersion</key>
  <string>$BUNDLE_VERSION</string>
  <key>LSApplicationCategoryType</key>
  <string>public.app-category.utilities</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
  <key>LSUIElement</key>
  <true/>
  <key>NSBonjourServices</key>
  <array>
    <string>_smspusher._tcp</string>
  </array>
  <key>NSLocalNetworkUsageDescription</key>
  <string>SmsPusher receives SMS messages from your paired Android phone on the local network.</string>
</dict>
</plist>
EOF

printf "APPL????" > "$CONTENTS_DIR/PkgInfo"

if command -v codesign >/dev/null 2>&1; then
  codesign --force --deep --sign - "$APP_BUNDLE" >/dev/null
fi

echo "$APP_BUNDLE"
