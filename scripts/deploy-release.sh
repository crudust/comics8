#!/usr/bin/env bash
set -euo pipefail

# ==========================================================
# Comics8 Universal Release & Cloud Deploy Orchestrator
#
# Architecture:
# - Orchestrated from local workstation (Mac) with zero build load.
# - Android release builds (APK/AAB) are fully offloaded to the
#   high-performance Linux build server over SSH.
# - Resulting APK & AAB are automatically fetched back to local dist/.
# - Desktop binaries (macOS / Windows) are built in cloud CI (GitHub Actions).
# ==========================================================

# Optional local overrides (e.g. custom host IPs)
if [[ -f "${HOME}/.comics8-deploy.env" ]]; then
    # shellcheck disable=SC1091
    source "${HOME}/.comics8-deploy.env"
fi

BUILD_HOST="${COMICS8_BUILD_HOST:-crudust@192.168.0.154}"
BUILD_DIR="${COMICS8_BUILD_DIR:-/home/crudust/projects/comics8}"
PUBLIC_REPO="${COMICS8_PUBLIC_REPO_URL:-git@github.com:crudust/comics8.git}"
SYNC_SERVER_HOST="${COMICS8_SERVER_HOST:-192.168.0.136}"
SYNC_SERVER_USER="${COMICS8_SERVER_USER:-crudust}"
SYNC_SERVER_DOWNLOAD_DIR="${COMICS8_SERVER_DOWNLOAD_DIR:-/home/crudust/comics8-sync/data/downloads}"
SYNC_SERVER_VERSION_FILE="${COMICS8_SERVER_VERSION_FILE:-/home/crudust/comics8-sync/data/version.json}"

# SSH/SCP common options. `known_hosts` may be unavailable (read-only home, fresh
# CI container), so host keys are accepted on first use everywhere.
SSH_OPTS=(-o StrictHostKeyChecking=no)
SCP_OPTS=(-o StrictHostKeyChecking=no)

# True when this script already runs on the build server, i.e. its own repository
# IS BUILD_DIR. Comparing resolved paths is reliable regardless of hostname or IP
# aliasing. On a Mac BUILD_DIR does not exist, so this stays false.
is_on_build_host() {
    [[ -n "${BUILD_DIR}" && -d "${BUILD_DIR}" ]] || return 1
    local here there
    here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)" || return 1
    there="$(cd "${BUILD_DIR}" && pwd -P)" || return 1
    [[ "${here}" == "${there}" ]]
}

# Parse options and arguments
TARGET="all"
NON_FLAG_ARGS=()
FETCH_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --desktop|-d) TARGET="desktop" ;;
        --android|-a) TARGET="android" ;;
        --all) TARGET="all" ;;
        --fetch|--pull) FETCH_ONLY=true ;;
        *) NON_FLAG_ARGS+=("$arg") ;;
    esac
done

if [[ "${#NON_FLAG_ARGS[@]}" -ge 4 && "${NON_FLAG_ARGS[3]}" =~ ^(all|desktop|android)$ ]]; then
    TARGET="${NON_FLAG_ARGS[3]}"
fi

VERSION_NAME="${NON_FLAG_ARGS[0]:-}"
VERSION_CODE="${NON_FLAG_ARGS[1]:-}"

# Read current version from app/build.gradle.kts if not specified
if [[ -z "${VERSION_NAME}" ]]; then
    VERSION_NAME=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2 || echo "1.0.0")
fi
if [[ -z "${VERSION_CODE}" ]]; then
    VERSION_CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | awk '{print $3}' || echo "1")
fi

DIST_DIR="dist"
APK_REL_PATH="app/build/outputs/apk/standalone/release/app-standalone-release.apk"
AAB_REL_PATH="app/build/outputs/bundle/playRelease/app-play-release.aab"
DIST_APK="${DIST_DIR}/comics8-v${VERSION_NAME}.apk"
DIST_AAB="${DIST_DIR}/comics8-v${VERSION_NAME}.aab"

prune_older_dist_artifacts() {
    echo "🧹 Pruning older build artifacts in local ${DIST_DIR}/ (keeping latest v${VERSION_NAME} only)..."
    find "${DIST_DIR}" -type f \( -name "*.apk" -o -name "*.aab" \) ! -name "comics8-v${VERSION_NAME}.*" -delete 2>/dev/null || true
}

# Fast-path: only fetch latest artifacts from remote build server
if [[ "$FETCH_ONLY" == "true" ]]; then
    mkdir -p "${DIST_DIR}"
    if is_on_build_host; then
        echo "📥 Build server detected locally; collecting artifacts from ${BUILD_DIR}..."
        cp "${BUILD_DIR}/${APK_REL_PATH}" "${DIST_APK}"
        cp "${BUILD_DIR}/${AAB_REL_PATH}" "${DIST_AAB}"
    else
        echo "📥 Fetching latest build artifacts from ${BUILD_HOST}..."
        scp "${SCP_OPTS[@]}" "${BUILD_HOST}:${BUILD_DIR}/${APK_REL_PATH}" "${DIST_APK}"
        scp "${SCP_OPTS[@]}" "${BUILD_HOST}:${BUILD_DIR}/${AAB_REL_PATH}" "${DIST_AAB}"
    fi
    prune_older_dist_artifacts
    echo "✅ Fetched to local dist/ (latest only):"
    ls -lh "${DIST_DIR}"
    exit 0
fi

# Release notes generation
if [[ -n "${NON_FLAG_ARGS[2]:-}" ]]; then
    RELEASE_NOTES="${NON_FLAG_ARGS[2]}"
else
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

echo "=================================================="
echo "🚀 Comics8 Deploying Release v${VERSION_NAME} (code ${VERSION_CODE})"
if is_on_build_host; then
    echo "Build Mode:   local (already on the build host)"
else
    echo "Build Server: ${BUILD_HOST}"
fi
echo "Target:       $(echo "$TARGET" | tr '[:lower:]' '[:upper:]')"
echo "Notes:        ${RELEASE_NOTES}"
echo "=================================================="

# Preflight: check build server reachability
if [[ "$TARGET" != "desktop" ]]; then
    if is_on_build_host; then
        echo "📡 Build host detected locally; skipping SSH reachability check."
        echo "  ✅ ${BUILD_DIR}"
    else
        echo "📡 Verifying connection to Linux build server (${BUILD_HOST})..."
        if ! ssh "${SSH_OPTS[@]}" -o ConnectTimeout=4 "${BUILD_HOST}" "test -d ${BUILD_DIR}" >/dev/null 2>&1; then
            echo "❌ Cannot connect to build server ${BUILD_HOST} or ${BUILD_DIR} does not exist."
            exit 1
        fi
        echo "  ✅ Build server connected."
    fi
fi

sed_inplace() {
    if sed --version >/dev/null 2>&1; then
        sed -i -E "$@"
    else
        sed -i '' -E "$@"
    fi
}

# 1. Sync version across modules
echo "🔧 1/5: Syncing codebase version to v${VERSION_NAME} (${VERSION_CODE})..."
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

# 2. Remote Build & Retrieve Android APK & AAB
build_android_on_server() {
    # The build steps are shared by both modes so local and remote builds cannot
    # drift apart. Kept as a plain string because the remote mode re-parses it
    # through `ssh ... "bash -lc '...'"`.
    local build_cmd
    build_cmd="set -a
if [ -f ~/.comics8-deploy.env ]; then source ~/.comics8-deploy.env; fi
set +a
export JAVA_HOME=\"\${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}\"
export ANDROID_HOME=\"\${ANDROID_HOME:-\$HOME/android-sdk}\"
export ANDROID_SDK_ROOT=\"\${ANDROID_SDK_ROOT:-\$HOME/android-sdk}\"
export PATH=\"\$JAVA_HOME/bin:\$ANDROID_SDK_ROOT/platform-tools:\$PATH\"
./gradlew assembleStandaloneRelease bundlePlayRelease
mkdir -p dist
cp \"${APK_REL_PATH}\" \"dist/comics8-v${VERSION_NAME}.apk\"
cp \"${AAB_REL_PATH}\" \"dist/comics8-v${VERSION_NAME}.aab\"
find dist -type f \( -name \"*.apk\" -o -name \"*.aab\" \) ! -name \"comics8-v${VERSION_NAME}.*\" -delete 2>/dev/null || true"

    if is_on_build_host; then
        # Already on the build host: run the same steps in place. Going through
        # SSH would require host keys for the local machine itself and buys
        # nothing.
        echo "🚀 Building Android release locally (${BUILD_DIR})..."
        ( cd "${BUILD_DIR}" && eval "${build_cmd}" )
        echo "📥 Collecting built APK & AAB into local ${DIST_DIR}/..."
        mkdir -p "${DIST_DIR}"
        cp "${BUILD_DIR}/${APK_REL_PATH}" "${DIST_APK}"
        cp "${BUILD_DIR}/${AAB_REL_PATH}" "${DIST_AAB}"
    else
        echo "📡 Flushing Mutagen sync before remote build..."
        if command -v mutagen >/dev/null 2>&1; then
            mutagen sync flush comics8 2>/dev/null || true
        fi

        echo "🚀 Delegating Android build to remote server (${BUILD_HOST})..."
        # shellcheck disable=SC2029
        ssh "${SSH_OPTS[@]}" "${BUILD_HOST}" "bash -lc '
            cd \"${BUILD_DIR}\" && ${build_cmd}
        '"

        echo "📥 Fetching built APK & AAB from build server into local ${DIST_DIR}/..."
        mkdir -p "${DIST_DIR}"
        scp "${SCP_OPTS[@]}" "${BUILD_HOST}:${BUILD_DIR}/${APK_REL_PATH}" "${DIST_APK}"
        scp "${SCP_OPTS[@]}" "${BUILD_HOST}:${BUILD_DIR}/${AAB_REL_PATH}" "${DIST_AAB}"
    fi

    if [[ ! -s "${DIST_APK}" ]]; then
        echo "❌ APK file was not retrieved or is empty: ${DIST_APK}"
        exit 1
    fi
    if [[ ! -s "${DIST_AAB}" ]]; then
        echo "❌ AAB file was not retrieved or is empty: ${DIST_AAB}"
        exit 1
    fi

    prune_older_dist_artifacts

    echo "  📦 Local release artifacts successfully collected in ${DIST_DIR}/ (latest only):"
    echo "     - ${DIST_APK} ($(du -h "${DIST_APK}" | cut -f1))"
    echo "     - ${DIST_AAB} ($(du -h "${DIST_AAB}" | cut -f1))"
}

case "$TARGET" in
    desktop)
        echo "ℹ️ Target is desktop-only. Android build skipped."
        ;;
    android|all|*)
        echo "📦 2/5: Building Android release..."
        build_android_on_server
        ;;
esac

# 3. Commit version bump in dev repo
echo "📝 3/5: Committing version bump..."
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
echo "📡 5/5: Uploading to Sync Server ($SYNC_SERVER_HOST)..."
if ssh "${SSH_OPTS[@]}" -o ConnectTimeout=3 "${SYNC_SERVER_USER}@${SYNC_SERVER_HOST}" "mkdir -p ${SYNC_SERVER_DOWNLOAD_DIR}" >/dev/null 2>&1; then
    if [[ "$TARGET" != "desktop" && -f "$DIST_APK" ]]; then
        scp "${SCP_OPTS[@]}" "$DIST_APK" "${SYNC_SERVER_USER}@${SYNC_SERVER_HOST}:${SYNC_SERVER_DOWNLOAD_DIR}/comics8-latest.apk"
    fi

    # Retrieve existing version.json to preserve non-targeted platform versions
    scp "${SCP_OPTS[@]}" "${SYNC_SERVER_USER}@${SYNC_SERVER_HOST}:${SYNC_SERVER_VERSION_FILE}" /tmp/comics8-existing-version.json 2>/dev/null || true

    COMICS8_TARGET="$TARGET" \
    COMICS8_VERSION_NAME="$VERSION_NAME" \
    COMICS8_VERSION_CODE="$VERSION_CODE" \
    COMICS8_RELEASE_NOTES="$RELEASE_NOTES" \
    python3 - <<'PY' >/tmp/comics8-version.json
import datetime, json, os, sys

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

# --- Update history (consumed by the in-app "About" screen) --------------
# Existing entries MUST be preserved, otherwise every deploy wipes history.
MAX_HISTORY_ENTRIES = 30
today = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
history = existing.get("history")
if not isinstance(history, list):
    history = []

new_entry = {
    "versionName": version_name,
    "versionCode": version_code,
    "date": today,
    "platform": target if target in ("android", "desktop") else "all",
    "releaseNotes": notes,
}

merged = []
found = False
for entry in history:
    if not isinstance(entry, dict):
        continue
    # Re-releasing the same version for the other platform must not create a
    # duplicate row; promote it to "all" instead.
    if entry.get("versionName") == version_name:
        existing_platform = entry.get("platform", "all")
        promoted = "all" if existing_platform != new_entry["platform"] else existing_platform
        entry = dict(entry)
        entry["platform"] = promoted
        entry["date"] = today
        entry["versionCode"] = version_code
        if notes:
            entry["releaseNotes"] = notes
        found = True
    merged.append(entry)

if not found:
    merged.insert(0, new_entry)

payload["history"] = merged[:MAX_HISTORY_ENTRIES]

json.dump(payload, sys.stdout, ensure_ascii=False, indent=2)
print()
PY
    scp "${SCP_OPTS[@]}" /tmp/comics8-version.json "${SYNC_SERVER_USER}@${SYNC_SERVER_HOST}:${SYNC_SERVER_VERSION_FILE}"
    rm -f /tmp/comics8-version.json /tmp/comics8-existing-version.json
    echo "  ✅ Synced version.json and binaries to ${SYNC_SERVER_HOST}"
else
    echo "  ⚠️ Sync server (${SYNC_SERVER_HOST}) unreachable via SSH. Skipped server upload."
fi

# 6. Publish to GitHub Releases (AAB is excluded from public release for security)
echo "🐙 Publishing to GitHub Releases (crudust/comics8)..."
RELEASE_FILES=()
if [[ "$TARGET" != "desktop" && -f "$DIST_APK" ]]; then
    TMP_LATEST_APK="build/release/comics8-latest.apk"
    mkdir -p "$(dirname "${TMP_LATEST_APK}")"
    cp "${DIST_APK}" "${TMP_LATEST_APK}"
    RELEASE_FILES+=("${TMP_LATEST_APK}")
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
