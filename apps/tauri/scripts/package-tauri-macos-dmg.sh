#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TAURI_DIR="$ROOT_DIR/apps/tauri"
VERSION_FILE="$ROOT_DIR/version.properties"

APP_NAME="${APP_NAME:-SmsPusher}"
VOLUME_NAME="${VOLUME_NAME:-SmsPusher}"
OUTPUT_DIR="${PACKAGE_OUTPUT_DIR:-$TAURI_DIR/build/package}"
APP_BUNDLE="${APP_BUNDLE:-$OUTPUT_DIR/$APP_NAME.app}"
BACKGROUND_SVG="$TAURI_DIR/resources/dmg-background.svg"
STAGING_DIR="${DMG_STAGING_DIR:-$TAURI_DIR/build/dmg-staging}"
BACKGROUND_RENDER_DIR="$TAURI_DIR/build/dmg-background-render"

if [ -z "${VERSION_NAME:-}" ] && [ -f "$VERSION_FILE" ]; then
  VERSION_NAME="$(sed -n 's/^VERSION_NAME=//p' "$VERSION_FILE" | head -n 1)"
fi
VERSION_NAME="${VERSION_NAME:-0.0.0}"

DMG_PATH="${DMG_PATH:-$OUTPUT_DIR/$APP_NAME-$VERSION_NAME.dmg}"
RW_DMG_PATH="${RW_DMG_PATH:-$OUTPUT_DIR/$APP_NAME-$VERSION_NAME-rw.dmg}"
MOUNT_DIR=""

cleanup() {
  if [ -n "$MOUNT_DIR" ] && mount | grep -F " on $MOUNT_DIR " >/dev/null 2>&1; then
    hdiutil detach "$MOUNT_DIR" -quiet || true
  fi
}
trap cleanup EXIT

if [ ! -d "$APP_BUNDLE" ]; then
  echo "missing app bundle: $APP_BUNDLE" >&2
  exit 1
fi

if [ ! -f "$BACKGROUND_SVG" ]; then
  echo "missing DMG background: $BACKGROUND_SVG" >&2
  exit 1
fi

case "$STAGING_DIR" in
  "$TAURI_DIR"/build/dmg-staging|/private/tmp/*|/tmp/*)
    rm -rf "$STAGING_DIR"
    ;;
  *)
    echo "refusing to remove staging directory outside known locations: $STAGING_DIR" >&2
    exit 1
    ;;
esac

rm -rf "$BACKGROUND_RENDER_DIR"
rm -f "$RW_DMG_PATH" "$DMG_PATH"
mkdir -p "$STAGING_DIR/.background" "$BACKGROUND_RENDER_DIR" "$OUTPUT_DIR"

ditto "$APP_BUNDLE" "$STAGING_DIR/$APP_NAME.app"
ln -s /Applications "$STAGING_DIR/Applications"

qlmanage -t -s 640 -o "$BACKGROUND_RENDER_DIR" "$BACKGROUND_SVG" >/dev/null 2>&1
if [ ! -f "$BACKGROUND_RENDER_DIR/dmg-background.svg.png" ]; then
  echo "failed to render DMG background" >&2
  exit 1
fi
cp "$BACKGROUND_RENDER_DIR/dmg-background.svg.png" "$STAGING_DIR/.background/dmg-background.png"

hdiutil create -volname "$VOLUME_NAME" -srcfolder "$STAGING_DIR" -format UDRW -fs HFS+ -ov "$RW_DMG_PATH"

ATTACH_OUTPUT="$(hdiutil attach -readwrite -noverify -noautoopen "$RW_DMG_PATH")"
MOUNT_DIR="$(printf '%s\n' "$ATTACH_OUTPUT" | awk '/\/Volumes\// { for (i = 1; i <= NF; i++) if ($i ~ /^\/Volumes\//) { print substr($0, index($0, $i)); exit } }')"

if [ -z "$MOUNT_DIR" ] || [ ! -d "$MOUNT_DIR" ]; then
  echo "failed to mount read-write DMG" >&2
  printf '%s\n' "$ATTACH_OUTPUT" >&2
  exit 1
fi

chflags hidden "$MOUNT_DIR/.background" || true

osascript <<APPLESCRIPT
tell application "Finder"
  tell disk "$VOLUME_NAME"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    set bounds of container window to {120, 120, 760, 500}

    set opts to icon view options of container window
    set arrangement of opts to not arranged
    set icon size of opts to 96
    set background picture of opts to file ".background:dmg-background.png"

    set position of item "$APP_NAME.app" to {145, 196}
    set position of item "Applications" to {455, 196}

    close
    open
    update without registering applications
    delay 1
  end tell
end tell
APPLESCRIPT

sync
hdiutil detach "$MOUNT_DIR" -quiet
MOUNT_DIR=""

hdiutil convert "$RW_DMG_PATH" -format UDZO -o "$DMG_PATH"
rm -f "$RW_DMG_PATH"
echo "$DMG_PATH"
