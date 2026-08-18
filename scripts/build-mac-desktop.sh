#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"

echo "🔨 Building macOS Desktop Application (.app)..."
cd "${ROOT_DIR}"
./gradlew :desktop:createDistributable

echo ""
echo "✅ Build Completed Successfully!"
echo "📁 macOS App: ${ROOT_DIR}/desktop/build/compose/binaries/main/app/Comics8.app"
