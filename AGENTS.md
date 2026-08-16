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
APK 파일만 새로 빌드할 때 사용합니다:
```bash
./scripts/build-apk.sh
# 또는
./gradlew assembleDebug
```
- **출력 APK 경로**: `app/build/outputs/apk/debug/app-debug.apk`

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

### 3.4. 단위 테스트 실행
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
./gradlew testDebugUnitTest
```

---

## 4. 프로젝트 구조 (Directory Structure)

```
Comics8/
├── app/
│   ├── build.gradle.kts          # 의존성, SDK 버전, Compose/Room/KSP 설정
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/comics8/monitor/
│       │   │   ├── ComicsApp.kt        # Application 클래스 (DB, WorkManager, Repository 초기화)
│       │   │   ├── MainActivity.kt     # Compose 테마/UI 진입점
│       │   │   ├── ui/
│       │   │   │   ├── ComicsAppUi.kt  # Compose 루트 컨테이너 및 진입점
│       │   │   │   ├── ListingViewModel.kt # 화면 상태 및 비즈니스 로직 관리
│       │   │   │   ├── browse/         # 작품 탐색 그리드/카드 (BrowsePane)
│       │   │   │   ├── series/         # 회차 상세 목록 및 행 (SeriesPane)
│       │   │   │   ├── reader/         # 만화 뷰어 (스크롤, 단면, 양쪽보기 등 ReaderPane)
│       │   │   │   ├── components/     # 공통 위젯, TopBar, 다이얼로그, 프롬프트
│       │   │   │   └── theme/Theme.kt  # 색상/타이포그래피 테마
│       │   │   ├── data/
│       │   │   │   ├── Models.kt       # 데이터 모델 (ComicItem, EpisodeItem 등)
│       │   │   │   ├── Catalog.kt      # 카탈로그/카테고리/사이트 정의
│       │   │   │   ├── ComicSite.kt    # 사이트 열거형 및 설정
│       │   │   │   ├── HtmlParsers.kt  # Jsoup 파서 (에피소드, 목록, 이미지)
│       │   │   │   ├── SearchParser.kt # 검색 응답 파서
│       │   │   │   ├── ToonClient.kt   # OkHttp 네트워크 클라이언트
│       │   │   │   ├── ToonRepository.kt # 네트워크 + Room 캐시 저장소
│       │   │   │   └── local/
│       │   │   │       └── AppDatabase.kt # Room Database, DAO, Entity 정의
│       │   │   └── work/
│       │   │       └── RefreshWorker.kt   # 백그라운드 주기적 업데이트 Worker
│       │   └── res/            # 리소스 (아이콘, 테마, 문자열 등)
│       └── test/               # 단위 테스트 및 테스트 리소스 (HTML/JSON fixture)
├── scripts/
│   ├── build-apk.sh            # APK 빌드 스크립트
│   └── run-mac.sh              # 에뮬레이터 생성/실행/설치 올인원 스크립트
├── build.gradle.kts
├── settings.gradle.kts
├── local.properties            # sdk.dir 설정
└── AGENTS.md                   # Agent 가이드 문서 (본 문서)
```

---

## 5. Agent 작업 지침 및 주의사항 (Guidelines for Agents)

1. **빌드/테스트 전 환경변수 필수**:
   - `JAVA_HOME`과 `ANDROID_SDK_ROOT`가 셸 세션에 올바르게 잡혀 있어야 `./gradlew` 및 `adb` 명령어가 정상 동작합니다.
2. **APK 빌드 vs 에뮬레이터 갱신 구분**:
   - `assembleDebug`는 디스크에 `app-debug.apk`를 생성하기만 합니다.
   - 에뮬레이터에 변경사항을 즉시 확인시키려면 `installDebug`와 `adb shell am start -n "com.comics8.monitor/.MainActivity"`를 함께 실행해야 합니다.
3. **Room Entity 변경 시 주의**:
   - Room 엔티티나 스키마 수정 시 KSP 코드 생성이 필요하며, DB 버전 업그레이드 시 마이그레이션 전략(`fallbackToDestructiveMigration()` 여부 확인)을 확인해야 합니다.
4. **테스트 추가 및 검증**:
   - 파싱 로직(`HtmlParsers`, `SearchParser`) 변경 시 `app/src/test/resources/`의 fixture를 활용하여 `testDebugUnitTest`를 반드시 통과시키세요.
