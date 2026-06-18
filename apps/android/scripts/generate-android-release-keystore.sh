#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)"
ANDROID_DIR="$ROOT_DIR/apps/android"
KEYSTORE_DIR="$ANDROID_DIR/release"
KEYSTORE_PATH="$KEYSTORE_DIR/SmsPusher-release.jks"
PROPERTIES_PATH="$ANDROID_DIR/keystore.properties"
KEY_ALIAS="smspusher-release"

if [ -f "$KEYSTORE_PATH" ]; then
  echo "Release keystore already exists: $KEYSTORE_PATH" >&2
  exit 1
fi

if [ -f "$PROPERTIES_PATH" ]; then
  echo "Release signing properties already exist: $PROPERTIES_PATH" >&2
  exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
else
  KEYTOOL="$(command -v keytool || true)"
fi

if [ -z "$KEYTOOL" ]; then
  echo "keytool was not found. Source scripts/dev-env.sh or install a JDK first." >&2
  exit 1
fi

read -r -s -p "Keystore password: " STORE_PASSWORD
echo
read -r -s -p "Confirm keystore password: " STORE_PASSWORD_CONFIRM
echo

if [ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]; then
  echo "Keystore passwords do not match." >&2
  exit 1
fi

if [ "${#STORE_PASSWORD}" -lt 6 ]; then
  echo "Keystore password must be at least 6 characters." >&2
  exit 1
fi

case "$STORE_PASSWORD" in
  *[[:space:]\\:=#!]*)
    echo "Keystore password must not contain whitespace or these characters: \\ : = # !" >&2
    exit 1
    ;;
esac

read -r -s -p "Key password, leave empty to reuse keystore password: " KEY_PASSWORD
echo
if [ -z "$KEY_PASSWORD" ]; then
  KEY_PASSWORD="$STORE_PASSWORD"
fi

if [ "${#KEY_PASSWORD}" -lt 6 ]; then
  echo "Key password must be at least 6 characters." >&2
  exit 1
fi

case "$KEY_PASSWORD" in
  *[[:space:]\\:=#!]*)
    echo "Key password must not contain whitespace or these characters: \\ : = # !" >&2
    exit 1
    ;;
esac

mkdir -p "$KEYSTORE_DIR"

"$KEYTOOL" -genkeypair \
  -v \
  -keystore "$KEYSTORE_PATH" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=SmsPusher, OU=Personal, O=Hippo2Cat, L=Unknown, ST=Unknown, C=US"

cat > "$PROPERTIES_PATH" <<EOF
storeFile=release/SmsPusher-release.jks
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
chmod 600 "$PROPERTIES_PATH"

echo "Created $KEYSTORE_PATH"
echo "Created $PROPERTIES_PATH"
echo "Keep both files private and backed up. They are required for future upgrades."
