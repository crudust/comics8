#!/bin/zsh
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
cd "$(dirname "$0")/.."
./gradlew assembleDebug
echo
echo "APK: $PWD/app/build/outputs/apk/debug/app-debug.apk"
