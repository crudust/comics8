#!/bin/zsh
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

AVD_NAME="comics8"
PACKAGE="com.comics8.monitor"
ACTIVITY=".MainActivity"

cd "$(dirname "$0")/.."

if [[ ! -x "$ANDROID_SDK_ROOT/emulator/emulator" ]]; then
  echo "에뮬레이터가 없습니다. 먼저 이미지를 설치하세요:"
  echo "  sdkmanager --sdk_root=\"$ANDROID_SDK_ROOT\" \"emulator\" \"system-images;android-34;default;arm64-v8a\""
  exit 1
fi

if ! avdmanager list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}$"; then
  echo "AVD ${AVD_NAME} 생성 중..."
  echo no | avdmanager create avd \
    --name "$AVD_NAME" \
    --package "system-images;android-34;default;arm64-v8a" \
    --device "pixel_5" \
    --force
  cfg="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  if [[ -f "$cfg" ]]; then
    {
      echo "hw.ramSize=2048"
      echo "hw.lcd.density=320"
      echo "disk.dataPartition.size=2G"
      echo "hw.keyboard=yes"
    } >> "$cfg"
  fi
fi

adb start-server >/dev/null
booted=false
if adb devices | awk 'NR>1 && $2=="device"{print $1}' | grep -q .; then
  booted=true
fi

if [[ "$booted" != true ]]; then
  echo "에뮬레이터 시작..."
  emulator -avd "$AVD_NAME" -netdelay none -netspeed full -no-boot-anim -no-snapshot-save >/tmp/comics8-emulator.log 2>&1 &
  adb wait-for-device
  echo -n "부팅 대기"
  for _ in {1..90}; do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      echo
      booted=true
      break
    fi
    echo -n "."
    sleep 2
  done
  echo
  if [[ "$booted" != true ]]; then
    echo "에뮬레이터 부팅이 너무 오래 걸립니다. /tmp/comics8-emulator.log 를 확인하세요."
    exit 1
  fi
fi

./gradlew installDebug
adb shell am start -n "${PACKAGE}/${ACTIVITY}" >/dev/null
echo "실행: ${PACKAGE}"
