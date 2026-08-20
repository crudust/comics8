#!/usr/bin/env bash
# SSH to the Mac builder, reset the dedicated clone, install Comics8.app,
# produce Comics8-mac.zip, and copy the zip back. Does not launch the app.
set -euo pipefail

if [[ -f "${HOME}/.comics8-deploy.env" ]]; then
    # shellcheck disable=SC1091
    source "${HOME}/.comics8-deploy.env"
fi

MAC_SSH="${COMICS8_MAC_SSH:-}"
MAC_BUILD_DIR="${COMICS8_MAC_BUILD_DIR:-}"
REPO_URL="${COMICS8_MAC_REPO_URL:-git@github.com:crudust/Comics8.git}"
REF="${1:-origin/main}"
LOCAL_ZIP="${2:-desktop/build/compose/binaries/main/Comics8-mac.zip}"
REMOTE_ZIP="${MAC_BUILD_DIR}/desktop/build/compose/binaries/main/Comics8-mac.zip"
VERSION_NAME="${COMICS8_VERSION_NAME:-}"
VERSION_CODE="${COMICS8_VERSION_CODE:-}"

if [[ -z "${MAC_SSH}" || -z "${MAC_BUILD_DIR}" ]]; then
    echo "Set COMICS8_MAC_SSH and COMICS8_MAC_BUILD_DIR in ~/.comics8-deploy.env"
    echo "Example: scripts/deploy.env.example"
    exit 1
fi

echo "Building Mac zip via ${MAC_SSH} (${REF} → ${MAC_BUILD_DIR})"

ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new "${MAC_SSH}" \
    COMICS8_MAC_BUILD_DIR="${MAC_BUILD_DIR}" \
    COMICS8_MAC_REPO_URL="${REPO_URL}" \
    COMICS8_MAC_REF="${REF}" \
    COMICS8_VERSION_NAME="${VERSION_NAME}" \
    COMICS8_VERSION_CODE="${VERSION_CODE}" \
    bash -s <<'REMOTE'
set -euo pipefail
DIR="${COMICS8_MAC_BUILD_DIR}"
URL="${COMICS8_MAC_REPO_URL}"
REF="${COMICS8_MAC_REF}"
if [[ ! -d "${DIR}/.git" ]]; then
    echo "Cloning ${URL} into ${DIR}"
    git clone "${URL}" "${DIR}"
fi
cd "${DIR}"
git fetch origin
git reset --hard "${REF}"

if [[ -n "${COMICS8_VERSION_NAME}" && -n "${COMICS8_VERSION_CODE}" ]]; then
    cat > desktop/src/main/kotlin/com/comics8/desktop/DesktopVersion.kt <<EOF
package com.comics8.desktop

object DesktopVersion {
    const val VERSION_NAME = "${COMICS8_VERSION_NAME}"
    const val VERSION_CODE = ${COMICS8_VERSION_CODE}
}
EOF
    sed -i '' -E "s/packageVersion = \"[^\"]+\"/packageVersion = \"${COMICS8_VERSION_NAME}\"/" desktop/build.gradle.kts
fi

JAVA_HOME_FOUND=""
if [[ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    JAVA_HOME_FOUND="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME_FOUND="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [[ -z "${JAVA_HOME_FOUND}" || ! -x "${JAVA_HOME_FOUND}/bin/java" ]]; then
    echo "JDK 17 not found on the Mac builder"
    exit 1
fi
export JAVA_HOME="${JAVA_HOME_FOUND}"
export PATH="${JAVA_HOME}/bin:${PATH}"

chmod +x ./gradlew
./gradlew --no-daemon :desktop:createDistributable -Pcomics8.desktopOnly=true

APP_DIR="${DIR}/desktop/build/compose/binaries/main/app"
if [[ ! -d "${APP_DIR}/Comics8.app" ]]; then
    echo "Comics8.app not found: ${APP_DIR}/Comics8.app"
    exit 1
fi
DEST_APP="${HOME}/Applications/Comics8.app"
mkdir -p "${HOME}/Applications"
rm -rf "${DEST_APP}"
cp -R "${APP_DIR}/Comics8.app" "${DEST_APP}"
xattr -dr com.apple.quarantine "${DEST_APP}" 2>/dev/null || true
echo "Installed ${DEST_APP}"

ZIP_PATH="${DIR}/desktop/build/compose/binaries/main/Comics8-mac.zip"
rm -f "${ZIP_PATH}"
(cd "${APP_DIR}" && zip -r -q "${ZIP_PATH}" "Comics8.app")
echo "Zipped ${ZIP_PATH}"
REMOTE

mkdir -p "$(dirname "${LOCAL_ZIP}")"
echo "Copying zip to ${LOCAL_ZIP}"
scp -o BatchMode=yes -o ConnectTimeout=5 "${MAC_SSH}:${REMOTE_ZIP}" "${LOCAL_ZIP}"
ls -lh "${LOCAL_ZIP}"
