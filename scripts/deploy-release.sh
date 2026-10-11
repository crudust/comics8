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

# Variables loaded by `source` are shell-local unless explicitly exported. Gradle
# reads signing secrets from the environment and must never receive them on its CLI.
for signing_var in \
    COMICS8_SIGNING_STORE_FILE \
    COMICS8_SIGNING_STORE_PASSWORD \
    COMICS8_SIGNING_KEY_ALIAS \
    COMICS8_SIGNING_KEY_PASSWORD; do
    if [[ -n "${!signing_var:-}" ]]; then
        export "${signing_var}"
    fi
done

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
# Parse options and arguments
TARGET="all"
NON_FLAG_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --desktop|-d) TARGET="desktop" ;;
        --android|-a) TARGET="android" ;;
        --all) TARGET="all" ;;
        *) NON_FLAG_ARGS+=("$arg") ;;
    esac
done

if [[ "${#NON_FLAG_ARGS[@]}" -ge 4 && "${NON_FLAG_ARGS[3]}" =~ ^(all|desktop|android)$ ]]; then
    TARGET="${NON_FLAG_ARGS[3]}"
fi

VERSION_NAME="${NON_FLAG_ARGS[0]:-1.0.0}"
VERSION_CODE="${NON_FLAG_ARGS[1]:-1}"
if [[ -n "${NON_FLAG_ARGS[2]:-}" ]]; then
    RELEASE_NOTES="${NON_FLAG_ARGS[2]}"
else
    # Auto-generate English release notes from commit history since previous tag
    PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
    AUTO_NOTES=""
    if [[ -n "${PREV_TAG}" ]]; then
        AUTO_NOTES=$(git log "${PREV_TAG}..HEAD" --oneline --no-merges \
            --grep="^chore: Bump version" --invert-grep \
            --grep="^sync:" --invert-grep \
            --format="- %s" 2>/dev/null || echo "")
    fi
    if [[ -n "${AUTO_NOTES}" ]]; then
        RELEASE_NOTES="${AUTO_NOTES}"
    else
        RELEASE_NOTES="Bug fixes and performance improvements"
    fi
fi
PUBLIC_REPO="${COMICS8_PUBLIC_REPO_URL:-git@github.com:crudust/comics8.git}"

REMOTE_BUILD_HOST="${COMICS8_BUILD_HOST:-crudust@192.168.0.154}"
REMOTE_BUILD_DIR="${COMICS8_BUILD_DIR:-/home/crudust/projects/comics8}"
NEED_REMOTE_BUILD=false

if [[ "$TARGET" != "desktop" ]]; then
    if [[ -z "${ANDROID_SDK_ROOT:-}" || ! -d "${ANDROID_SDK_ROOT}" ]] || ! ./gradlew -q :app:validateReleaseSigning >/dev/null 2>&1; then
        NEED_REMOTE_BUILD=true
    fi

    if [[ "$NEED_REMOTE_BUILD" == "true" ]]; then
        echo "📡 Local Android SDK/signing not available. Checking remote Linux build server (${REMOTE_BUILD_HOST})..."
        if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=3 "${REMOTE_BUILD_HOST}" "test -d ${REMOTE_BUILD_DIR}" >/dev/null 2>&1; then
            echo "  ✅ Connected to remote build server (${REMOTE_BUILD_HOST}). Android build will be delegated."
        else
            echo "❌ ANDROID_SDK_ROOT is not set locally and remote build server (${REMOTE_BUILD_HOST}) is unreachable."
            exit 1
        fi
    fi
else
    echo "ℹ️ Target is desktop-only: skipping Android SDK and signing validation."
fi

echo "=================================================="
echo "🚀 Comics8 Deploying Release v${VERSION_NAME} (code ${VERSION_CODE})"
echo "Target: $(echo "$TARGET" | tr '[:lower:]' '[:upper:]')"
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
DESKTOP_PKG_VER="${VERSION_NAME%%-*}"
if [[ "${DESKTOP_PKG_VER}" =~ ^0\. ]]; then
    DESKTOP_PKG_VER="1.${DESKTOP_PKG_VER#0.}"
fi
sed_inplace "s/packageVersion = \"[^\"]+\"/packageVersion = \"${DESKTOP_PKG_VER}\"/" desktop/build.gradle.kts

cat <<VERSION_EOF > desktop/src/main/kotlin/DesktopVersion.kt
package com.comics8.desktop

object DesktopVersion {
    const val VERSION_NAME = "${VERSION_NAME}"
    const val VERSION_CODE = ${VERSION_CODE}
}
VERSION_EOF

# 2. Build binaries depending on target
APK_PATH="app/build/outputs/apk/standalone/release/app-standalone-release.apk"
AAB_PATH="app/build/outputs/bundle/playRelease/app-play-release.aab"
DIST_DIR="dist"
DIST_APK="${DIST_DIR}/comics8-v${VERSION_NAME}.apk"
DIST_AAB="${DIST_DIR}/comics8-v${VERSION_NAME}.aab"
DIST_LATEST_APK="${DIST_DIR}/comics8-latest.apk"

build_android() {
    if [[ "$NEED_REMOTE_BUILD" == "true" ]]; then
        echo "📡 Flushing Mutagen sync before remote build..."
        if command -v mutagen >/dev/null 2>&1; then
            mutagen sync flush comics8 2>/dev/null || true
        fi
        echo "🚀 Running Android build on remote host (${REMOTE_BUILD_HOST})..."
        ssh "${REMOTE_BUILD_HOST}" "bash -lc '
            cd \"${REMOTE_BUILD_DIR}\" && \
            set -a && \
            if [ -f ~/.comics8-deploy.env ]; then source ~/.comics8-deploy.env; fi && \
            set +a && \
            export JAVA_HOME=\"\${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}\" && \
            export ANDROID_HOME=\"\${ANDROID_HOME:-\$HOME/android-sdk}\" && \
            export ANDROID_SDK_ROOT=\"\${ANDROID_SDK_ROOT:-\$HOME/android-sdk}\" && \
            export PATH=\"\$JAVA_HOME/bin:\$ANDROID_SDK_ROOT/platform-tools:\$PATH\" && \
            ./gradlew assembleStandaloneRelease bundlePlayRelease
        '"

        echo "📥 Fetching built APK & AAB from remote host..."
        mkdir -p "$(dirname "$APK_PATH")" "$(dirname "$AAB_PATH")"
        scp "${REMOTE_BUILD_HOST}:${REMOTE_BUILD_DIR}/${APK_PATH}" "${APK_PATH}"
        scp "${REMOTE_BUILD_HOST}:${REMOTE_BUILD_DIR}/${AAB_PATH}" "${AAB_PATH}"
    else
        ./gradlew assembleStandaloneRelease bundlePlayRelease
    fi

    if [ ! -f "$APK_PATH" ]; then
        echo "❌ APK file not found: $APK_PATH"
        exit 1
    fi
    if [ ! -f "$AAB_PATH" ]; then
        echo "❌ AAB file not found: $AAB_PATH"
        exit 1
    fi

    # Collect artifacts into project dist/ folder
    mkdir -p "${DIST_DIR}"
    cp "${APK_PATH}" "${DIST_APK}"
    cp "${AAB_PATH}" "${DIST_AAB}"
    cp "${APK_PATH}" "${DIST_LATEST_APK}"
    echo "  📦 Release artifacts collected in ${DIST_DIR}/:"
    echo "     - ${DIST_APK}"
    echo "     - ${DIST_AAB}"
}

case "$TARGET" in
    desktop)
        echo "ℹ️ Desktop binaries (Windows & Mac) are built automatically by GitHub Actions on tag push."
        ;;
    android|all|*)
        echo "📦 2/5: Building Android Release APK & AAB (Windows & Mac are built via GitHub Actions)..."
        build_android
        ;;
esac


# 3. Commit version bump in dev repo
echo "📝 3/5: Committing version bump to dev repo..."
git add app/build.gradle.kts desktop/build.gradle.kts desktop/src/main/kotlin/DesktopVersion.kt
if ! git diff --staged --quiet; then
    git commit -m "chore: Bump version to ${VERSION_NAME} (code ${VERSION_CODE})"
fi
TAG_NAME="v${VERSION_NAME}"
git tag -f "${TAG_NAME}"
git push origin main "${TAG_NAME}" -f

# 4. Sync open-source modules to public repo
echo "🔄 4/5: Syncing open-source repository..."
./scripts/sync-public-repo.sh "${PUBLIC_REPO}"

# 5. Upload binaries and version.json to Sync Server (if reachable)
SERVER_HOST="${COMICS8_SERVER_HOST:-192.168.0.136}"
SERVER_USER="${COMICS8_SERVER_USER:-crudust}"
SERVER_DOWNLOAD_DIR="${COMICS8_SERVER_DOWNLOAD_DIR:-/home/crudust/projects/comics8/server/data/downloads}"
SERVER_VERSION_FILE="${COMICS8_SERVER_VERSION_FILE:-/home/crudust/projects/comics8/server/data/version.json}"

echo "📡 5/5: Uploading to Sync Server ($SERVER_HOST)..."
if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=3 "${SERVER_USER}@${SERVER_HOST}" "mkdir -p ${SERVER_DOWNLOAD_DIR}" >/dev/null 2>&1; then
    if [[ "$TARGET" != "desktop" && -f "$APK_PATH" ]]; then
        scp "$DIST_LATEST_APK" "${SERVER_USER}@${SERVER_HOST}:${SERVER_DOWNLOAD_DIR}/comics8-latest.apk"
    fi

    # Retrieve existing version.json to preserve non-targeted platform version
    scp -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_HOST}:${SERVER_VERSION_FILE}" /tmp/comics8-existing-version.json 2>/dev/null || true

    COMICS8_TARGET="$TARGET" \
    COMICS8_VERSION_NAME="$VERSION_NAME" \
    COMICS8_VERSION_CODE="$VERSION_CODE" \
    COMICS8_RELEASE_NOTES="$RELEASE_NOTES" \
    python3 - <<'PY' >/tmp/comics8-version.json
import json, os, sys

target = os.environ.get("COMICS8_TARGET", "all")
version_name = os.environ["COMICS8_VERSION_NAME"]
version_code = int(os.environ["COMICS8_VERSION_CODE"])
notes = os.environ["COMICS8_RELEASE_NOTES"]
tag = f"v{version_name}"

existing = {}
if os.path.exists("/tmp/comics8-existing-version.json"):
    try:
        with open("/tmp/comics8-existing-version.json", "r", encoding="utf-8") as f:
            existing = json.load(f)
    except Exception:
        existing = {}

android = existing.get("android") or {}
desktop = existing.get("desktop") or {}

if target in ("all", "android"):
    android = {
        "versionCode": version_code,
        "versionName": version_name,
        "downloadUrl": f"https://github.com/crudust/comics8/releases/download/{tag}/comics8-latest.apk",
        "releaseNotes": notes,
    }

if target in ("all", "desktop"):
    desktop = {
        "versionCode": version_code,
        "versionName": version_name,
        "downloadUrl": f"https://github.com/crudust/comics8/releases/download/{tag}/Comics8-mac.zip",
        "releaseNotes": notes,
        "windowsDownloadUrl": f"https://github.com/crudust/comics8/releases/download/{tag}/Comics8-win.zip",
    }

payload = {"android": android, "desktop": desktop}
if "serverUrl" in existing:
    payload["serverUrl"] = existing["serverUrl"]

json.dump(payload, sys.stdout, ensure_ascii=False, indent=2)
print()
PY
    scp /tmp/comics8-version.json "${SERVER_USER}@${SERVER_HOST}:${SERVER_VERSION_FILE}"
    rm -f /tmp/comics8-version.json /tmp/comics8-existing-version.json
    echo "  ✅ Synced version.json and binaries to ${SERVER_HOST}"
else
    echo "  ⚠️ Sync server (${SERVER_HOST}) unreachable via SSH. Skipped server upload."
fi

# 6. Publish to GitHub Releases (AAB is excluded from public release for security)
echo "🐙 6/6: Publishing to GitHub Releases (crudust/comics8)..."
RELEASE_FILES=()
if [[ "$TARGET" != "desktop" && -f "$DIST_LATEST_APK" ]]; then
    RELEASE_FILES+=("$DIST_LATEST_APK")
fi

if gh release view "${TAG_NAME}" --repo crudust/comics8 >/dev/null 2>&1; then
    echo "  ⬆️ Updating assets for release ${TAG_NAME}..."
    if [ ${#RELEASE_FILES[@]} -gt 0 ]; then
        gh release upload "${TAG_NAME}" "${RELEASE_FILES[@]}" --clobber --repo crudust/comics8
    fi
else
    echo "  🎉 Creating release ${TAG_NAME}..."
    if [ ${#RELEASE_FILES[@]} -gt 0 ]; then
        gh release create "${TAG_NAME}" "${RELEASE_FILES[@]}" \
          --repo crudust/comics8 \
          --title "Comics8 ${TAG_NAME}" \
          --notes "${RELEASE_NOTES}"
    else
        gh release create "${TAG_NAME}" \
          --repo crudust/comics8 \
          --title "Comics8 ${TAG_NAME}" \
          --notes "${RELEASE_NOTES}"
    fi
fi

# 7. Prune older releases (Keep only the latest 2 releases)
echo "🧹 Pruning old releases (keeping latest 2)..."
ALL_RELEASES=$(gh release list --repo crudust/comics8 --limit 100 --json tagName --jq '.[].tagName' 2>/dev/null || true)
RELEASE_COUNT=0
for r_tag in $ALL_RELEASES; do
    RELEASE_COUNT=$((RELEASE_COUNT + 1))
    if [ $RELEASE_COUNT -gt 2 ]; then
        echo "  🗑️ Deleting older release: $r_tag..."
        gh release delete "$r_tag" --repo crudust/comics8 --cleanup-tag -y >/dev/null 2>&1 || true
    fi
done

echo "=================================================="
echo "🎉 Release deployed successfully! (Target: ${TARGET})"
echo "• GitHub Release: https://github.com/crudust/comics8/releases/tag/${TAG_NAME}"
if [[ "$TARGET" != "desktop" ]]; then
    echo "• Local Artifacts (dist/):"
    echo "  - Standalone APK: ${DIST_APK}"
    echo "  - Play Store AAB: ${DIST_AAB} (Private, Play Console only)"
    echo "• Android APK URL: https://github.com/crudust/comics8/releases/download/${TAG_NAME}/comics8-latest.apk"
fi
if [[ "$TARGET" != "android" ]]; then
    echo "• Windows Zip:    https://github.com/crudust/comics8/releases/download/${TAG_NAME}/Comics8-win.zip (GitHub Actions)"
    echo "• Mac Desktop:    https://github.com/crudust/comics8/releases/download/${TAG_NAME}/Comics8-mac.zip (GitHub Actions)"
fi
echo "=================================================="
