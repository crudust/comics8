#!/usr/bin/env bash
set -e

# ==========================================================
# Comics8 Release Deploy Script
# Builds Android APK + macOS Desktop App, zips them,
# uploads to Comics8 server (VM 101), and updates version.json.
# ==========================================================

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

SERVER_HOST="192.168.0.136"
SERVER_USER="crudust"
SERVER_DOWNLOAD_DIR="/home/crudust/comics8-sync/data/downloads"
SERVER_VERSION_FILE="/home/crudust/comics8-sync/data/version.json"

VERSION_NAME="${1:-1.0.0}"
VERSION_CODE="${2:-1}"
RELEASE_NOTES="${3:-버그 수정 및 성능 개선}"

echo "=================================================="
echo "🚀 Comics8 Deploying Release v${VERSION_NAME} (code ${VERSION_CODE})"
echo "Notes: ${RELEASE_NOTES}"
echo "=================================================="

# 0. Sync version across modules
echo "🔧 Syncing codebase version..."
sed -i '' -E "s/versionCode = [0-9]+/versionCode = ${VERSION_CODE}/" app/build.gradle.kts
sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"${VERSION_NAME}\"/" app/build.gradle.kts

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
echo "📦 2/4: Building macOS Desktop App..."
./gradlew :desktop:createDistributable

MAC_APP_DIR="desktop/build/compose/binaries/main/app"
MAC_ZIP_PATH="desktop/build/compose/binaries/main/Comics8-mac.zip"

if [ ! -d "$MAC_APP_DIR/Comics8.app" ]; then
    echo "❌ macOS App not found: $MAC_APP_DIR/Comics8.app"
    exit 1
fi

echo "📦 Zipping Comics8.app..."
rm -f "$MAC_ZIP_PATH"
(cd "$MAC_APP_DIR" && zip -r -q "../Comics8-mac.zip" "Comics8.app")

# 3. Upload binaries and server code to Server
echo "🚀 3/4: Uploading server code and binaries to server ($SERVER_HOST)..."
ssh -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_HOST}" "mkdir -p ${SERVER_DOWNLOAD_DIR} && rm -rf ${SERVER_DOWNLOAD_DIR}/*"

if [ -f "server/server.py" ]; then
    echo "🐍 Updating server.py and restarting comics8-sync service..."
    scp server/server.py "${SERVER_USER}@${SERVER_HOST}:/home/crudust/comics8-sync/server.py"
    ssh -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_HOST}" "sudo -n systemctl restart comics8-sync"
fi

scp "$APK_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/comics8-latest.apk"
scp "$MAC_ZIP_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/Comics8-mac.zip"

# 4. Update version.json on Server
echo "📝 4/4: Updating version.json on server..."
VERSION_JSON=$(cat <<JSONEOF
{
  "android": {
    "versionCode": ${VERSION_CODE},
    "versionName": "${VERSION_NAME}",
    "downloadUrl": "/api/comics8/download/comics8-latest.apk",
    "releaseNotes": "${RELEASE_NOTES}"
  },
  "desktop": {
    "versionCode": ${VERSION_CODE},
    "versionName": "${VERSION_NAME}",
    "downloadUrl": "/api/comics8/download/Comics8-mac.zip",
    "releaseNotes": "${RELEASE_NOTES}"
  }
}
JSONEOF
)

ssh -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_HOST}" "echo '${VERSION_JSON}' > ${SERVER_VERSION_FILE}"

echo "=================================================="
echo "🎉 배포가 성공적으로 완료되었습니다!"
echo "• Version Info:  https://homeassistant.tail1946af.ts.net/api/comics8/version"
echo "• Android APK:   https://homeassistant.tail1946af.ts.net/api/comics8/download/comics8-latest.apk"
echo "• Mac Desktop:   https://homeassistant.tail1946af.ts.net/api/comics8/download/Comics8-mac.zip"
echo "=================================================="
