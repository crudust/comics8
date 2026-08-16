# AGENTS.md

Comics8 Android 프로젝트 작업 시 필요한 환경 정보, 빌드/실행 명령어, 아키텍처 및 작업 가이드입니다.

---

## 1. 프로젝트 개요 (Project Overview)
- **앱 이름**: Comics8 Monitor
- **패키지명**: `com.comics8.monitor`
- **메인 액티비티**: `com.comics8.monitor.MainActivity`
- **기술 스택**:
  - **언어/런타임**: Kotlin (JVM 17), Android SDK 35 (minSdk 26)
  - **UI**: Jetpack Compose + Material3 + Material Icons Extended + Coil
  - **아키텍처**: MVVM (ViewModel + Compose UI)
  - **로컬 DB**: Room 2.6.1 (KSP 기반 컴파일)
  - **네트워크 / 파싱**: OkHttp 4.12.0, Jsoup 1.18.3
  - **백그라운드 작업**: AndroidX WorkManager 2.10.0
  - **테스트**: JUnit 4, Google Truth, Jsoup, JSON

---

## 2. 개발 환경 및 필수 경로 (Environment Setup)

macOS 환경 기준으로 기본 설정된 환경 변수 및 SDK 경로입니다:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
```

- **AVD 에뮬레이터 이름**: `comics8` (`system-images;android-34;default;arm64-v8a`)

---

## 3. 주요 빌드 및 실행 명령어 (Build & Run Commands)

### 3.1. APK 단독 빌드
정식 서명된 Release APK 파일을 빌드할 때 사용합니다:
```bash
./scripts/build-apk.sh
# 또는
./gradlew assembleRelease
```
- **출력 APK 경로**: `app/build/outputs/apk/release/app-release.apk`

### 3.2. 에뮬레이터 기동 + 설치 + 앱 실행
에뮬레이터가 꺼져 있으면 부팅 후 자동 설치 및 실행합니다:
```bash
./scripts/run-mac.sh
```

### 3.3. 실행 중인 에뮬레이터에 즉시 갱신 (Hot Install & Launch)
에뮬레이터(`emulator-5554`)가 이미 켜져 있는 상태에서 코드 수정분을 즉시 반영할 때:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

./gradlew installDebug
adb shell am start -n "com.comics8.monitor/.MainActivity"
```

### 3.5. macOS 데스크톱 앱 실행 (Desktop Run)
```bash
./scripts/run-desktop.sh
# 또는
./gradlew :desktop:run
```

### 3.6. macOS 데스크톱 배포본 빌드 (.app 및 .dmg)
```bash
./scripts/build-mac-desktop.sh
# 또는
./gradlew :desktop:createDistributable :desktop:packageDmg
```
- **출력 App 경로**: `desktop/build/compose/binaries/main/app/Comics8.app`
- **출력 DMG 경로**: `desktop/build/compose/binaries/main/dmg/Comics8-1.0.0.dmg`

---

## 4. 프로젝트 구조 (Directory Structure)

```
Comics8/
├── core/                       # 공통 모듈 (파서, 모델, 네트워크, 동기화 상수)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/comics8/core/
│       ├── model/              # Catalog, ToonItem, EpisodeItem, DualSpread, ViewMode 등
│       ├── parser/             # ComicSite, HtmlParsers, SearchParser
│       ├── network/            # ToonClient
│       └── sync/               # SyncConstants (서버 URL, 동기화 키 생성기)
├── app/                        # Android 모듈 (APK)
│   ├── build.gradle.kts
│   └── src/main/java/com/comics8/monitor/
├── desktop/                    # macOS Desktop 모듈 (.app / .dmg)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/comics8/desktop/
│       ├── Main.kt
│       ├── data/               # SQLite JDBC, 리포지토리, 동기화 매니저
│       └── ui/                 # Compose Desktop UI (Browse, Series, Reader, History)
├── scripts/
│   ├── build-apk.sh            # Android Release APK 빌드
│   ├── run-mac.sh              # Android 에뮬레이터 실행 및 설치
│   ├── build-mac-desktop.sh    # macOS Desktop .app 및 .dmg 빌드
│   └── run-desktop.sh          # macOS Desktop 앱 직접 실행
├── build.gradle.kts
├── settings.gradle.kts
├── local.properties
└── AGENTS.md
```

---

## 5. Agent 작업 지침 및 주의사항 (Guidelines for Agents)

1. **빌드/테스트 전 환경변수 필수**:
   - `JAVA_HOME`과 `ANDROID_SDK_ROOT`가 셸 세션에 올바르게 잡혀 있어야 `./gradlew` 및 `adb` 명령어가 정상 동작합니다.
2. **Android 빌드와 macOS Desktop 빌드 구분**:
   - Android APK: `./scripts/build-apk.sh` 또는 `./gradlew assembleRelease`
   - macOS Desktop: `./scripts/build-mac-desktop.sh` 또는 `./gradlew :desktop:createDistributable :desktop:packageDmg`
   - macOS 직접 실행: `./scripts/run-desktop.sh` 또는 `./gradlew :desktop:run`
3. **Room Entity 변경 시 주의**:
   - Room 엔티티나 스키마 수정 시 KSP 코드 생성이 필요하며, DB 버전 업그레이드 시 마이그레이션 전략(`fallbackToDestructiveMigration()` 여부 확인)을 확인해야 합니다.
4. **테스트 추가 및 검증**:
   - 파싱 로직(`HtmlParsers`, `SearchParser`) 변경 시 `app/src/test/resources/`의 fixture를 활용하여 `testDebugUnitTest`를 반드시 통과시키세요.
