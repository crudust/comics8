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

cat <<EOF > desktop/src/main/kotlin/com/comics8/desktop/DesktopVersion.kt
package com.comics8.desktop

object DesktopVersion {
    const val VERSION_NAME = "${VERSION_NAME}"
    const val VERSION_CODE = ${VERSION_CODE}
}
