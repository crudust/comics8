#!/usr/bin/env bash
# Build Comics8.app on this Mac (no Apple Developer cert, no Android SDK).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_DIR="${HOME}/Applications"
APP_NAME="Comics8.app"

echo "=================================================="
echo "Comics8 로컬 맥 빌드"
echo "=================================================="

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "이 스크립트는 macOS 전용입니다."
    exit 1
fi

if [[ "$(uname -m)" != "arm64" ]]; then
    echo "지금 배포/빌드는 Apple Silicon(M1 이후)만 지원합니다."
    exit 1
fi

ensure_brew() {
    if command -v brew >/dev/null 2>&1; then
        return
    fi
    echo "Homebrew가 없습니다. 설치합니다. (암호를 물을 수 있습니다)"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    if [[ -x /opt/homebrew/bin/brew ]]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
    elif [[ -x /usr/local/bin/brew ]]; then
        eval "$(/usr/local/bin/brew shellenv)"
    fi
    if ! command -v brew >/dev/null 2>&1; then
        echo "Homebrew 설치에 실패했습니다. https://brew.sh 를 확인하세요."
        exit 1
    fi
}

resolve_java17() {
    local home=""
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
    if [[ -z "${home}" ]]; then
        local candidates=(
            "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
            "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        )
        local c
        for c in "${candidates[@]}"; do
            if [[ -x "${c}/bin/java" ]]; then
                home="${c}"
                break
            fi
        done
    fi
    if [[ -n "${home}" && -x "${home}/bin/java" ]]; then
        echo "${home}"
        return 0
    fi
    return 1
}

JAVA_HOME_FOUND="$(resolve_java17 || true)"
if [[ -z "${JAVA_HOME_FOUND}" ]]; then
    echo "JDK 17이 없습니다. Homebrew로 openjdk@17을 설치합니다."
    ensure_brew
    brew install openjdk@17
    JAVA_HOME_FOUND="$(resolve_java17 || true)"
fi
if [[ -z "${JAVA_HOME_FOUND}" ]]; then
    echo "JDK 17을 찾지 못했습니다."
    exit 1
fi

export JAVA_HOME="${JAVA_HOME_FOUND}"
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME}"
"${JAVA_HOME}/bin/java" -version

cd "${ROOT_DIR}"
chmod +x ./gradlew
echo "빌드 중... (처음이면 몇 분 걸립니다)"
./gradlew --no-daemon :desktop:createDistributable -Pcomics8.desktopOnly=true

SRC_APP="${ROOT_DIR}/desktop/build/compose/binaries/main/app/${APP_NAME}"
if [[ ! -d "${SRC_APP}" ]]; then
    echo "빌드는 끝났지만 앱을 찾지 못했습니다: ${SRC_APP}"
    exit 1
fi

mkdir -p "${DEST_DIR}"
rm -rf "${DEST_DIR}/${APP_NAME}"
cp -R "${SRC_APP}" "${DEST_DIR}/${APP_NAME}"
xattr -dr com.apple.quarantine "${DEST_DIR}/${APP_NAME}" 2>/dev/null || true

echo ""
echo "완료: ${DEST_DIR}/${APP_NAME}"
echo "=================================================="
