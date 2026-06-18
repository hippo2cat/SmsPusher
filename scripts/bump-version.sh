#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/version.properties"
TAURI_PACKAGE_JSON="$ROOT_DIR/apps/tauri/package.json"
TAURI_PACKAGE_LOCK="$ROOT_DIR/apps/tauri/package-lock.json"
TAURI_CONF="$ROOT_DIR/apps/tauri/src-tauri/tauri.conf.json"
TAURI_CARGO_TOML="$ROOT_DIR/apps/tauri/src-tauri/Cargo.toml"
TAURI_CARGO_LOCK="$ROOT_DIR/apps/tauri/src-tauri/Cargo.lock"

usage() {
  cat >&2 <<USAGE
Usage:
  scripts/bump-version.sh android <version-name> [android-version-code]
  scripts/bump-version.sh desktop <version-name> [desktop-build-number]
  scripts/bump-version.sh all <version-name> [android-version-code] [desktop-build-number]

Legacy:
  scripts/bump-version.sh <version-name> [android-version-code]

Examples:
  scripts/bump-version.sh android 0.4.0
  scripts/bump-version.sh desktop 0.4.0 7
  scripts/bump-version.sh all 0.4.0
USAGE
}

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

read_property() {
  local key="$1"
  if [ ! -f "$VERSION_FILE" ]; then
    return 0
  fi
  sed -n "s/^${key}=//p" "$VERSION_FILE" | head -n 1
}

validate_semver() {
  local value="$1"
  local label="$2"
  if [[ ! "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
    fail "$label must be SemVer, for example 0.4.0 or 0.4.0-rc.1"
  fi
}

validate_positive_integer() {
  local value="$1"
  local label="$2"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    fail "$label must be a positive integer"
  fi
}

next_integer() {
  local key="$1"
  local current
  current="$(read_property "$key")"
  validate_positive_integer "$current" "$key"
  echo "$((current + 1))"
}

update_json_version() {
  local file="$1"
  local version_name="$2"
  node -e '
    const fs = require("fs");
    const [file, version] = process.argv.slice(1);
    const data = JSON.parse(fs.readFileSync(file, "utf8"));
    data.version = version;
    if (data.packages && data.packages[""]) {
      data.packages[""].version = version;
    }
    fs.writeFileSync(file, JSON.stringify(data, null, 2) + "\n");
  ' "$file" "$version_name"
}

sync_desktop_files() {
  local desktop_version_name="$1"
  update_json_version "$TAURI_PACKAGE_JSON" "$desktop_version_name"
  update_json_version "$TAURI_PACKAGE_LOCK" "$desktop_version_name"
  update_json_version "$TAURI_CONF" "$desktop_version_name"

  DESKTOP_VERSION_NAME="$desktop_version_name" perl -0pi -e 's/(\[package\]\nname = "smspusher-tauri"\nversion = ")[^"]+/$1$ENV{DESKTOP_VERSION_NAME}/' "$TAURI_CARGO_TOML"
  DESKTOP_VERSION_NAME="$desktop_version_name" perl -0pi -e 's/(\[\[package\]\]\nname = "smspusher-tauri"\nversion = ")[^"]+/$1$ENV{DESKTOP_VERSION_NAME}/' "$TAURI_CARGO_LOCK"
}

write_version_file() {
  cat > "$VERSION_FILE" <<VERSION
ANDROID_VERSION_NAME=$android_version_name
ANDROID_VERSION_CODE=$android_version_code
DESKTOP_VERSION_NAME=$desktop_version_name
DESKTOP_BUILD_NUMBER=$desktop_build_number
VERSION
}

command_name="${1:-}"
if [ -z "$command_name" ]; then
  usage
  exit 1
fi

legacy_all=false
if [[ "$command_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  legacy_all=true
  set -- all "$@"
  command_name="all"
fi

current_android_version_name="$(read_property ANDROID_VERSION_NAME)"
if [ -z "$current_android_version_name" ]; then
  current_android_version_name="$(read_property VERSION_NAME)"
fi
current_android_version_code="$(read_property ANDROID_VERSION_CODE)"
current_desktop_version_name="$(read_property DESKTOP_VERSION_NAME)"
if [ -z "$current_desktop_version_name" ]; then
  current_desktop_version_name="$(read_property VERSION_NAME)"
fi
current_desktop_build_number="$(read_property DESKTOP_BUILD_NUMBER)"
if [ -z "$current_desktop_build_number" ]; then
  current_desktop_build_number="$current_android_version_code"
fi

android_version_name="$current_android_version_name"
android_version_code="$current_android_version_code"
desktop_version_name="$current_desktop_version_name"
desktop_build_number="$current_desktop_build_number"

case "$command_name" in
  android)
    android_version_name="${2:-}"
    android_version_code="${3:-}"
    [ -n "$android_version_name" ] || { usage; exit 1; }
    validate_semver "$android_version_name" "android version"
    if [ -z "$android_version_code" ]; then
      android_version_code="$(next_integer ANDROID_VERSION_CODE)"
    fi
    validate_positive_integer "$android_version_code" "android-version-code"
    ;;
  desktop)
    desktop_version_name="${2:-}"
    desktop_build_number="${3:-}"
    [ -n "$desktop_version_name" ] || { usage; exit 1; }
    validate_semver "$desktop_version_name" "desktop version"
    if [ -z "$desktop_build_number" ]; then
      desktop_build_number="$(next_integer DESKTOP_BUILD_NUMBER)"
    fi
    validate_positive_integer "$desktop_build_number" "desktop-build-number"
    sync_desktop_files "$desktop_version_name"
    ;;
  all)
    version_name="${2:-}"
    [ -n "$version_name" ] || { usage; exit 1; }
    validate_semver "$version_name" "version"
    android_version_name="$version_name"
    desktop_version_name="$version_name"
    android_version_code="${3:-}"
    desktop_build_number="${4:-}"
    if [ -z "$android_version_code" ]; then
      android_version_code="$(next_integer ANDROID_VERSION_CODE)"
    fi
    if [ -z "$desktop_build_number" ]; then
      if [ "$legacy_all" = true ] && [ -n "${3:-}" ]; then
        desktop_build_number="$android_version_code"
      else
        desktop_build_number="$(next_integer DESKTOP_BUILD_NUMBER)"
      fi
    fi
    validate_positive_integer "$android_version_code" "android-version-code"
    validate_positive_integer "$desktop_build_number" "desktop-build-number"
    sync_desktop_files "$desktop_version_name"
    ;;
  *)
    usage
    exit 1
    ;;
esac

validate_semver "$android_version_name" "android version"
validate_positive_integer "$android_version_code" "ANDROID_VERSION_CODE"
validate_semver "$desktop_version_name" "desktop version"
validate_positive_integer "$desktop_build_number" "DESKTOP_BUILD_NUMBER"
write_version_file

case "$command_name" in
  android)
    echo "Updated Android version to $android_version_name ($android_version_code)"
    ;;
  desktop)
    echo "Updated desktop version to $desktop_version_name ($desktop_build_number)"
    ;;
  all)
    echo "Updated Android version to $android_version_name ($android_version_code)"
    echo "Updated desktop version to $desktop_version_name ($desktop_build_number)"
    ;;
esac
