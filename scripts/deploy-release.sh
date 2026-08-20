#!/usr/bin/env bash
set -euo pipefail

# ==========================================================
# Comics8 Release Deploy Script
# Builds Android APK (+ macOS Desktop App on Darwin),
# uploads to Comics8 server (VM 101), and updates version.json.
# ==========================================================

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -x "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java" ]]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    elif [[ -x "/usr/lib/jvm/java-17-openjdk-amd64/bin/java" ]]; then
        export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
    fi
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
    if [[ -d "/opt/homebrew/share/android-commandlinetools" ]]; then
        export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
    elif [[ -d "${HOME}/android-sdk" ]]; then
        export ANDROID_SDK_ROOT="${HOME}/android-sdk"
    fi
fi
export ANDROID_HOME="${ANDROID_SDK_ROOT:-}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/platform-tools:}$PATH"

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "❌ JAVA_HOME is not set to a JDK 17 install"
    exit 1
fi
if [[ -z "${ANDROID_SDK_ROOT:-}" || ! -d "${ANDROID_SDK_ROOT}" ]]; then
    echo "❌ ANDROID_SDK_ROOT is not set"
    exit 1
fi

SERVER_HOST="192.168.0.136"
SERVER_USER="crudust"
SERVER_DOWNLOAD_DIR="/home/crudust/comics8-sync/data/downloads"
SERVER_VERSION_FILE="/home/crudust/comics8-sync/data/version.json"
SSH=(ssh -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_HOST}")

VERSION_NAME="${1:-1.0.0}"
VERSION_CODE="${2:-1}"
RELEASE_NOTES="${3:-버그 수정 및 성능 개선}"
BUILD_MAC=0
if [[ "$(uname -s)" == "Darwin" ]]; then
    BUILD_MAC=1
fi

echo "=================================================="
echo "🚀 Comics8 Deploying Release v${VERSION_NAME} (code ${VERSION_CODE})"
echo "Notes: ${RELEASE_NOTES}"
if [[ "$BUILD_MAC" -eq 0 ]]; then
    echo "ℹ️  Non-macOS host: Android APK only (keeping existing Mac zip)"
fi
echo "=================================================="

sed_inplace() {
    if sed --version >/dev/null 2>&1; then
        sed -i -E "$@"
    else
        sed -i '' -E "$@"
    fi
}

# 0. Sync version across modules
echo "🔧 Syncing codebase version..."
sed_inplace "s/versionCode = [0-9]+/versionCode = ${VERSION_CODE}/" app/build.gradle.kts
sed_inplace "s/versionName = \"[^\"]+\"/versionName = \"${VERSION_NAME}\"/" app/build.gradle.kts

cat <<EOF > desktop/src/main/kotlin/com/comics8/desktop/DesktopVersion.kt
package com.comics8.desktop

object DesktopVersion {
    const val VERSION_NAME = "${VERSION_NAME}"
    const val VERSION_CODE = ${VERSION_CODE}
}
EOF

# 1. Build Android Release APK
echo "📦 1/4: Building Android Release APK..."
./gradlew assembleRelease

APK_PATH="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK file not found: $APK_PATH"
    exit 1
fi

# 2. Build macOS Desktop App & ZIP
MAC_ZIP_PATH="desktop/build/compose/binaries/main/Comics8-mac.zip"
if [[ "$BUILD_MAC" -eq 1 ]]; then
    echo "📦 2/4: Building macOS Desktop App..."
    ./gradlew :desktop:createDistributable

    MAC_APP_DIR="desktop/build/compose/binaries/main/app"
    if [ ! -d "$MAC_APP_DIR/Comics8.app" ]; then
        echo "❌ macOS App not found: $MAC_APP_DIR/Comics8.app"
        exit 1
    fi

    echo "📦 Zipping Comics8.app..."
    rm -f "$MAC_ZIP_PATH"
    (cd "$MAC_APP_DIR" && zip -r -q "../Comics8-mac.zip" "Comics8.app")
else
    echo "📦 2/4: Skipping macOS Desktop App (not Darwin)"
fi

# 3. Upload binaries and server code to Server
echo "🚀 3/4: Uploading server code and binaries to server ($SERVER_HOST)..."
"${SSH[@]}" "mkdir -p ${SERVER_DOWNLOAD_DIR}"
if [[ "$BUILD_MAC" -eq 1 ]]; then
    "${SSH[@]}" "rm -rf ${SERVER_DOWNLOAD_DIR}/*"
else
    "${SSH[@]}" "rm -f ${SERVER_DOWNLOAD_DIR}/comics8-latest.apk"
fi

if [ -f "server/server.py" ]; then
    echo "🐍 Updating server.py and restarting comics8-podman service..."
    scp server/server.py "${SERVER_USER}@${SERVER_HOST}:/home/crudust/comics8-sync/server.py"
    "${SSH[@]}" "sudo -n systemctl restart comics8-podman"
fi

scp "$APK_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/comics8-latest.apk"
if [[ "$BUILD_MAC" -eq 1 ]]; then
    scp "$MAC_ZIP_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/Comics8-mac.zip"
fi

# 4. Update version.json on Server
echo "📝 4/4: Updating version.json on server..."
"${SSH[@]}" "cat ${SERVER_VERSION_FILE}" > /tmp/comics8-version-current.json || echo '{}' > /tmp/comics8-version-current.json
COMICS8_VERSION_NAME="$VERSION_NAME" \
COMICS8_VERSION_CODE="$VERSION_CODE" \
COMICS8_RELEASE_NOTES="$RELEASE_NOTES" \
COMICS8_BUILD_MAC="$BUILD_MAC" \
python3 - <<'PY' >/tmp/comics8-version.json
import json, os, sys

with open("/tmp/comics8-version-current.json", encoding="utf-8") as f:
    raw = f.read().strip() or "{}"
try:
    current = json.loads(raw)
except json.JSONDecodeError:
    current = {}

android = {
    "versionCode": int(os.environ["COMICS8_VERSION_CODE"]),
    "versionName": os.environ["COMICS8_VERSION_NAME"],
    "downloadUrl": "/api/comics8/download/comics8-latest.apk",
    "releaseNotes": os.environ["COMICS8_RELEASE_NOTES"],
}
desktop = dict(current.get("desktop") or {})
if os.environ["COMICS8_BUILD_MAC"] == "1":
    desktop = {
        "versionCode": int(os.environ["COMICS8_VERSION_CODE"]),
        "versionName": os.environ["COMICS8_VERSION_NAME"],
        "downloadUrl": "/api/comics8/download/Comics8-mac.zip",
        "releaseNotes": os.environ["COMICS8_RELEASE_NOTES"],
    }
else:
    desktop.setdefault("downloadUrl", "/api/comics8/download/Comics8-mac.zip")

json.dump({"android": android, "desktop": desktop}, sys.stdout, ensure_ascii=False, indent=2)
print()
PY
scp /tmp/comics8-version.json "${SERVER_USER}@${SERVER_HOST}:${SERVER_VERSION_FILE}"
rm -f /tmp/comics8-version.json /tmp/comics8-version-current.json

echo "=================================================="
echo "🎉 배포가 성공적으로 완료되었습니다!"
echo "• Version Info:  https://homeassistant.tail1946af.ts.net/api/comics8/version"
echo "• Android APK:   https://homeassistant.tail1946af.ts.net/api/comics8/download/comics8-latest.apk"
echo "• Mac Desktop:   https://homeassistant.tail1946af.ts.net/api/comics8/download/Comics8-mac.zip"
if [[ "$BUILD_MAC" -eq 0 ]]; then
    echo "• Desktop zip was left unchanged (built on macOS only)"
fi
echo "=================================================="
