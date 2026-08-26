#!/usr/bin/env bash
set -euo pipefail

# ==========================================================
# Comics8 Universal Release & Cloud Deploy Script
# Builds APK & Windows Zip on Linux, syncs open source to
# crudust/comics8, pushes release tags, and publishes to GitHub.
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
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/platform-tools:}$HOME/.local/bin:$PATH"

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "❌ JAVA_HOME is not set to a JDK 17 install"
    exit 1
fi
if [[ -z "${ANDROID_SDK_ROOT:-}" || ! -d "${ANDROID_SDK_ROOT}" ]]; then
    echo "❌ ANDROID_SDK_ROOT is not set"
    exit 1
fi

VERSION_NAME="${1:-1.0.0}"
VERSION_CODE="${2:-1}"
RELEASE_NOTES="${3:-버그 수정 및 성능 개선}"
PUBLIC_REPO="${COMICS8_PUBLIC_REPO_URL:-git@github.com:crudust/comics8.git}"

echo "=================================================="
echo "🚀 Comics8 Deploying Release v${VERSION_NAME} (code ${VERSION_CODE})"
echo "Notes: ${RELEASE_NOTES}"
echo "=================================================="

sed_inplace() {
    if sed --version >/dev/null 2>&1; then
        sed -i -E "$@"
    else
        sed -i '' -E "$@"
    fi
}

# 1. Sync version across modules
echo "🔧 1/5: Syncing codebase version..."
sed_inplace "s/versionCode = [0-9]+/versionCode = ${VERSION_CODE}/" app/build.gradle.kts
sed_inplace "s/versionName = \"[^\"]+\"/versionName = \"${VERSION_NAME}\"/" app/build.gradle.kts
sed_inplace "s/packageVersion = \"[^\"]+\"/packageVersion = \"${VERSION_NAME}\"/" desktop/build.gradle.kts

cat <<VERSION_EOF > desktop/src/main/kotlin/DesktopVersion.kt
package com.comics8.desktop

object DesktopVersion {
    const val VERSION_NAME = "${VERSION_NAME}"
    const val VERSION_CODE = ${VERSION_CODE}
}
VERSION_EOF

# 2. Build Android Release APK & Windows Portable Zip
echo "📦 2/5: Building Android Release APK & Windows Portable Zip..."
./gradlew assembleRelease :desktop:packageWindowsZip

APK_PATH="app/build/outputs/apk/release/app-release.apk"
WIN_ZIP_PATH="desktop/build/compose/binaries/main/Comics8-win.zip"
MAC_ZIP_PATH="desktop/build/compose/binaries/main/Comics8-mac.zip"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK file not found: $APK_PATH"
    exit 1
fi

# 3. Commit version bump in dev repo
echo "📝 3/5: Committing version bump to dev repo..."
git add app/build.gradle.kts desktop/build.gradle.kts desktop/src/main/kotlin/DesktopVersion.kt
if ! git diff --staged --quiet; then
    git commit -m "chore: Bump version to ${VERSION_NAME} (code ${VERSION_CODE})"
fi
git push origin main

# 4. Sync open-source modules to public repo
echo "🔄 4/5: Syncing open-source repository..."
./scripts/sync-public-repo.sh "${PUBLIC_REPO}"

# 5. Upload binaries and version.json to Sync Server (if reachable)
SERVER_HOST="${COMICS8_SERVER_HOST:-192.168.0.136}"
SERVER_USER="${COMICS8_SERVER_USER:-crudust}"
SERVER_DOWNLOAD_DIR="${COMICS8_SERVER_DOWNLOAD_DIR:-/home/crudust/comics8-sync/data/downloads}"
SERVER_VERSION_FILE="${COMICS8_SERVER_VERSION_FILE:-/home/crudust/comics8-sync/data/version.json}"

echo "📡 5/5: Uploading to Sync Server ($SERVER_HOST)..."
if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=3 "${SERVER_USER}@${SERVER_HOST}" "mkdir -p ${SERVER_DOWNLOAD_DIR}" >/dev/null 2>&1; then
    scp "$APK_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/comics8-latest.apk"
    if [ -f "$WIN_ZIP_PATH" ]; then
        scp "$WIN_ZIP_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/Comics8-win.zip"
    fi
    if [ -f "$MAC_ZIP_PATH" ]; then
        scp "$MAC_ZIP_PATH" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/Comics8-mac.zip"
    fi

    COMICS8_VERSION_NAME="$VERSION_NAME" \
    COMICS8_VERSION_CODE="$VERSION_CODE" \
    COMICS8_RELEASE_NOTES="$RELEASE_NOTES" \
    python3 - <<'PY' >/tmp/comics8-version.json
import json, os, sys

android = {
    "versionCode": int(os.environ["COMICS8_VERSION_CODE"]),
    "versionName": os.environ["COMICS8_VERSION_NAME"],
    "downloadUrl": "/api/comics8/download/comics8-latest.apk",
    "releaseNotes": os.environ["COMICS8_RELEASE_NOTES"],
}
desktop = {
    "versionCode": int(os.environ["COMICS8_VERSION_CODE"]),
    "versionName": os.environ["COMICS8_VERSION_NAME"],
    "downloadUrl": "/api/comics8/download/Comics8-mac.zip",
    "releaseNotes": os.environ["COMICS8_RELEASE_NOTES"],
    "windowsDownloadUrl": "/api/comics8/download/Comics8-win.zip",
}
json.dump({"android": android, "desktop": desktop}, sys.stdout, ensure_ascii=False, indent=2)
print()
PY
    scp /tmp/comics8-version.json "${SERVER_USER}@${SERVER_HOST}:${SERVER_VERSION_FILE}"
    rm -f /tmp/comics8-version.json
    echo "  ✅ Synced version.json and binaries to ${SERVER_HOST}"
else
    echo "  ⚠️ Sync server (${SERVER_HOST}) unreachable via SSH. Skipped server upload."
fi

# 6. Publish to GitHub Releases
echo "🐙 6/6: Publishing to GitHub Releases (crudust/comics8)..."
RELEASE_FILES=("$APK_PATH#comics8-latest.apk")
if [ -f "$WIN_ZIP_PATH" ]; then
    RELEASE_FILES+=("$WIN_ZIP_PATH")
fi
if [ -f "$MAC_ZIP_PATH" ]; then
    RELEASE_FILES+=("$MAC_ZIP_PATH")
fi

TAG_NAME="v${VERSION_NAME}"
if gh release view "${TAG_NAME}" --repo crudust/comics8 >/dev/null 2>&1; then
    echo "  ⬆️ Updating assets for release ${TAG_NAME}..."
    gh release upload "${TAG_NAME}" "${RELEASE_FILES[@]}" --clobber --repo crudust/comics8
else
    echo "  🎉 Creating release ${TAG_NAME}..."
    gh release create "${TAG_NAME}" "${RELEASE_FILES[@]}" \
      --repo crudust/comics8 \
      --title "Comics8 ${TAG_NAME}" \
      --notes "${RELEASE_NOTES}"
fi

# Generate Homebrew Cask Formula if Mac zip exists
if [ -f "$MAC_ZIP_PATH" ]; then
    MAC_SHA256=$(sha256sum "$MAC_ZIP_PATH" | awk '{print $1}')
    mkdir -p build/homebrew
    sed "s/__VERSION__/${VERSION_NAME}/g; s/__SHA256__/${MAC_SHA256}/g" templates/homebrew/comics8.rb > build/homebrew/comics8.rb
    echo "🍺 Generated Homebrew Cask: build/homebrew/comics8.rb (SHA256: ${MAC_SHA256})"
fi

echo "=================================================="
echo "🎉 배포가 성공적으로 완료되었습니다!"
echo "• Version Info:  https://homeassistant.tail1946af.ts.net/api/comics8/version"
echo "• GitHub Release: https://github.com/crudust/comics8/releases/tag/${TAG_NAME}"
echo "• Android APK:    https://github.com/crudust/comics8/releases/download/${TAG_NAME}/comics8-latest.apk"
echo "• Windows Zip:    https://github.com/crudust/comics8/releases/download/${TAG_NAME}/Comics8-win.zip"
if [ -f "$MAC_ZIP_PATH" ]; then
    echo "• Mac Desktop:    https://github.com/crudust/comics8/releases/download/${TAG_NAME}/Comics8-mac.zip"
fi
echo "=================================================="
