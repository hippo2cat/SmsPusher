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
  scripts/bump-version.sh <version-name> [build-number]

Examples:
  scripts/bump-version.sh 0.4.0
  scripts/bump-version.sh 0.4.0 12
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

current_build_number() {
  local current
  current="$(read_property BUILD_NUMBER)"
  echo "$current"
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
  local version_name="$1"
  update_json_version "$TAURI_PACKAGE_JSON" "$version_name"
  update_json_version "$TAURI_PACKAGE_LOCK" "$version_name"
  update_json_version "$TAURI_CONF" "$version_name"

  VERSION_NAME="$version_name" perl -0pi -e 's/(\[package\]\nname = "smspusher-tauri"\nversion = ")[^"]+/$1$ENV{VERSION_NAME}/' "$TAURI_CARGO_TOML"
  VERSION_NAME="$version_name" perl -0pi -e 's/(\[\[package\]\]\nname = "smspusher-tauri"\nversion = ")[^"]+/$1$ENV{VERSION_NAME}/' "$TAURI_CARGO_LOCK"
}

write_version_file() {
  cat > "$VERSION_FILE" <<VERSION
VERSION_NAME=$version_name
BUILD_NUMBER=$build_number
VERSION
}

command_name="${1:-}"
if [ -z "$command_name" ]; then
  usage
  exit 1
fi

case "$command_name" in
  android|desktop|all)
    fail "platform-specific version bumps were removed; use scripts/bump-version.sh <version-name> [build-number]"
    ;;
esac

version_name="$command_name"
build_number="${2:-}"
validate_semver "$version_name" "version"
if [ -z "$build_number" ]; then
  current="$(current_build_number)"
  validate_positive_integer "$current" "BUILD_NUMBER"
  build_number="$((current + 1))"
fi
validate_positive_integer "$build_number" "BUILD_NUMBER"
sync_desktop_files "$version_name"
write_version_file

echo "Updated version to $version_name ($build_number)"
