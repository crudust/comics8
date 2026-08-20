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

릴리즈 배포는 Linux 개발서버에서 합니다. Android SDK와 JDK 17이 필요합니다.

```bash
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

Android는 에뮬레이터로 돌리지 않습니다. 폰은 인앱 업데이트로 APK를 받습니다. Mac `.app`은 배포 시 SSH로 빌더 Mac의 `/Applications/Comics8.app`을 덮어쓰고, zip은 서버에도 올립니다. 실행 중인 앱은 인앱 업데이트로 받을 수 있습니다.

---

## 3. 주요 빌드 및 실행 명령어 (Build & Run Commands)

### 3.1. APK 단독 빌드
정식 서명된 Release APK 파일을 빌드할 때 사용합니다:
```bash
./gradlew assembleRelease
```
- **출력 APK 경로**: `app/build/outputs/apk/release/app-release.apk`

### 3.2. macOS 데스크톱 앱 실행 (개발용)
Mac 코딩 클론에서:
```bash
./scripts/run-desktop.sh
# 또는
./gradlew :desktop:run
```

### 3.3. macOS 첫 설치 (`/Applications`)
빌더 Mac에서 한 번:
```bash
./scripts/build-mac-local.sh
```

### 3.4. 릴리즈 배포
Linux에서 APK를 빌드하고, SSH로 Mac `.app`을 만든 뒤 서버에 둘 다 올립니다. Mac SSH는 `~/.comics8-deploy.env` (예: `scripts/deploy.env.example`).
```bash
./scripts/deploy-release.sh [versionName] [versionCode] [releaseNotes]
# 예시:
./scripts/deploy-release.sh 1.4.2 21 "Hitomi 진행도 표시"
```

---

## 4. 프로젝트 구조 (Directory Structure)

```
Comics8/
├── core/                       # 공통 모듈 (파서, 모델, 네트워크, 동기화 상수, 업데이트 체커)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/comics8/core/
│       ├── model/              # ToonItem, EpisodeItem, DualSpread, ViewMode, UpdateModels 등
│       ├── source/             # ComicSource, eleven/, hitomi/
│       ├── network/            # ToonClient
│       └── sync/               # SyncConstants, AppUpdateChecker
├── app/                        # Android 모듈 (APK)
│   ├── build.gradle.kts
│   └── src/main/java/com/comics8/monitor/
├── desktop/                    # macOS Desktop 모듈 (.app / .dmg)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/comics8/desktop/
│       ├── Main.kt
│       ├── data/               # SQLite JDBC, 리포지토리, 동기화/업데이트 매니저
│       └── ui/                 # Compose Desktop UI (Browse, Series, Reader, History)
├── scripts/
│   ├── build-mac-local.sh          # Mac에서 /Applications 첫 설치
│   ├── update-mac-app-remote.sh    # Linux→Mac SSH 빌드/설치/zip
│   ├── run-desktop.sh              # Mac에서 데스크톱 앱 직접 실행
│   ├── deploy-release.sh           # APK + Mac zip 서버 배포
│   └── deploy.env.example          # ~/.comics8-deploy.env 예시
├── build.gradle.kts
├── settings.gradle.kts
├── local.properties
└── AGENTS.md
```

---

## 5. Agent 작업 지침 및 주의사항 (Guidelines for Agents)

1. **빌드/테스트 전 환경변수 필수**:
   - `JAVA_HOME`과 `ANDROID_SDK_ROOT`가 셸 세션에 올바르게 잡혀 있어야 `./gradlew`가 정상 동작합니다. Android는 에뮬레이터/`adb`로 실행하지 않습니다.
2. **릴리즈는 Linux `deploy-release.sh`만 사용**:
   - 푸시(`origin/main`) 후 배포. Mac 빌더는 GitHub `main`을 받아 `.app`을 만들고 `/Applications`에 덮어쓴 뒤 zip을 서버에 올립니다.
   - 앱을 실행하지는 않습니다. 이미 켜 둔 앱은 인앱 업데이트로 받습니다.
   - Mac 코딩 클론(`Documents/Comics8`)은 건드리지 않습니다. 빌드는 `Documents/Mac/Comics8`입니다.
3. **Room Entity 변경 시 주의**:
   - Room 엔티티나 스키마 수정 시 KSP 코드 생성이 필요하며, DB 버전 업그레이드 시 마이그레이션 전략(`fallbackToDestructiveMigration()` 여부 확인)을 확인해야 합니다.
4. **테스트 추가 및 검증**:
   - 파싱 로직 변경 시 `core/src/test/resources/` fixture로 `:core:test`를 통과시키세요. Android 모듈 테스트는 `:app:testDebugUnitTest`입니다.
