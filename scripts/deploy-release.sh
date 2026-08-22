#!/usr/bin/env bash
set -euo pipefail

# ==========================================================
# Comics8 Release Deploy Script
# Linux: APK here, .app via SSH to the Mac builder, upload both.
# ==========================================================

if [[ -f "${HOME}/.comics8-deploy.env" ]]; then
    # shellcheck disable=SC1091
    source "${HOME}/.comics8-deploy.env"
fi

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
export COMICS8_VERSION_NAME="${VERSION_NAME}"
export COMICS8_VERSION_CODE="${VERSION_CODE}"

echo "=================================================="
echo "Comics8 Deploying Release v${VERSION_NAME} (code ${VERSION_CODE})"
echo "Notes: ${RELEASE_NOTES}"
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
sed_inplace "s/packageVersion = \"[^\"]+\"/packageVersion = \"${VERSION_NAME}\"/" desktop/build.gradle.kts

cat <<EOF > desktop/src/main/kotlin/com/comics8/desktop/DesktopVersion.kt
package com.comics8.desktop

object DesktopVersion {
    const val VERSION_NAME = "${VERSION_NAME}"
    const val VERSION_CODE = ${VERSION_CODE}
}
EOF

# 1. Build Android Release APK
echo "📦 1/5: Building Android Release APK..."
./gradlew assembleRelease

APK_PATH="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK file not found: $APK_PATH"
    exit 1
fi

# 2. Build macOS Desktop App on the Mac builder
MAC_ZIP_PATH="desktop/build/compose/binaries/main/Comics8-mac.zip"
HAVE_MAC_ZIP=0
if [[ -z "${COMICS8_MAC_SSH:-}" ]]; then
    echo "COMICS8_MAC_SSH is not set. See scripts/deploy.env.example"
    exit 1
fi
echo "📦 2/5: Building macOS Desktop App via ${COMICS8_MAC_SSH}..."
if ./scripts/update-mac-app-remote.sh origin/main "${MAC_ZIP_PATH}"; then
    HAVE_MAC_ZIP=1
else
    echo "⚠️  Mac .app build failed. Continuing with Android APK only."
fi

# 3. Upload binaries and server code to Server
echo "🚀 3/5: Uploading server code and binaries to server ($SERVER_HOST)..."
"${SSH[@]}" "mkdir -p ${SERVER_DOWNLOAD_DIR}"
if [[ "$HAVE_MAC_ZIP" -eq 1 ]]; then
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
if [[ "$HAVE_MAC_ZIP" -eq 1 ]]; then
    scp "$MAC_ZIP_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/Comics8-mac.zip"
fi

# 4. Update version.json on Server
echo "📝 4/5: Updating version.json on server..."
"${SSH[@]}" "cat ${SERVER_VERSION_FILE}" > /tmp/comics8-version-current.json || echo '{}' > /tmp/comics8-version-current.json
COMICS8_VERSION_NAME="$VERSION_NAME" \
COMICS8_VERSION_CODE="$VERSION_CODE" \
COMICS8_RELEASE_NOTES="$RELEASE_NOTES" \
COMICS8_BUILD_MAC="$HAVE_MAC_ZIP" \
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

# 5. Verify endpoint health
echo "🔍 5/5: Verifying deployment health via Funnel..."
if curl -s -m 6 -f "https://homeassistant.tail1946af.ts.net/api/comics8/version" >/dev/null 2>&1; then
    echo "✅ Funnel endpoint is healthy and returning 200 OK"
else
    echo "⚠️  Funnel endpoint did not respond via HTTPS, checking local backend port 8905..."
    if curl -s -m 5 -f "http://${SERVER_HOST}:8905/version" >/dev/null 2>&1; then
        echo "✅ Local backend (8905) is healthy."
    fi
fi

echo "=================================================="
echo "🎉 배포가 성공적으로 완료되었습니다!"
echo "• Version Info:  https://homeassistant.tail1946af.ts.net/api/comics8/version"
echo "• Android APK:   https://homeassistant.tail1946af.ts.net/api/comics8/download/comics8-latest.apk"
echo "• Mac Desktop:   https://homeassistant.tail1946af.ts.net/api/comics8/download/Comics8-mac.zip"
if [[ "$HAVE_MAC_ZIP" -eq 0 ]]; then
    echo "• Desktop zip was left unchanged (no Mac .app this run)"
fi
echo "=================================================="
