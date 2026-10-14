# AGENTS.md

Comics8 Android 프로젝트 작업 시 필요한 환경 정보, 빌드/실행 명령어, 아키텍처 및 작업 가이드입니다.

---

## 1. 프로젝트 개요 (Project Overview)
- **앱 이름**: Comics8 Monitor
- **패키지명**: `com.comics8.monitor`
- **메인 액티비티**: `com.comics8.monitor.MainActivity`
- **기술 스택**:
  - **언어/런타임**: Kotlin (JVM 17), Android SDK 36 (minSdk 26)
  - **UI**: Jetpack Compose + Material3 + Material Icons Extended + Coil
  - **아키텍처**: MVVM (ViewModel + Compose UI)
  - **로컬 DB**: Room 2.6.1 (KSP 기반 컴파일)
  - **네트워크 / 파싱**: OkHttp 4.12.0, Jsoup 1.18.3
  - **백그라운드 작업**: AndroidX WorkManager 2.10.0
  - **테스트**: JUnit 4, Google Truth, Jsoup, JSON

---

## 2. 개발 환경 및 필수 경로 (Environment Setup)

- **Mac (로컬 개발 워크스테이션)**:
  - 가벼운 코딩, 테스트, 데스크톱 앱 실행 및 배포 오케스트레이션 전용 (Mac에 무거운 빌드 부하를 주지 않음).
- **Linux 개발/빌드 서버 (`crudust@192.168.0.154`)**:
  - Android SDK, JDK 17, 릴리즈 서명 키스토어가 완비되어 있으며 무거운 Android Release 빌드(APK/AAB)를 전담합니다.
  ```bash
  export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
  export ANDROID_SDK_ROOT="$HOME/android-sdk"
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
  export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
  ```

Android는 에뮬레이터로 돌리지 않습니다. 폰은 인앱 업데이트로 APK를 받습니다. macOS 앱은 GitHub Actions(`macos-latest`)에서 태그 푸시 시 자동 빌드되어 GitHub Releases에 배포되며, 실행 중인 Mac 앱은 인앱 업데이트로 직접 다운로드받아 갱신됩니다.

---

## 3. 주요 빌드 및 실행 명령어 (Build & Run Commands)

### 3.1. APK 및 AAB 단독 빌드 (Linux 빌드 서버 전용)
정식 서명된 Release APK/AAB를 빌드할 때 사용합니다:
```bash
# GitHub / 자체 배포용 APK 및 Google Play 배포용 AAB
./gradlew assembleStandaloneRelease bundlePlayRelease
```
- **출력 APK 경로**: `dist/comics8-v{version}.apk` (또는 `app/build/outputs/apk/standalone/release/app-standalone-release.apk`)
- **출력 AAB 경로**: `dist/comics8-v{version}.aab` (또는 `app/build/outputs/bundle/playRelease/app-play-release.aab`)
- **Windows / Mac 데스크톱**: 태그 푸시 시 GitHub Actions에서 자동 빌드되어 GitHub Releases에 배포됩니다.

### 3.2. macOS 데스크톱 앱 실행 (개발용)
Mac 코딩 클론에서:
```bash
./scripts/run-desktop.sh
# 또는
./gradlew :desktop:run
```

### 3.3. 릴리즈 배포
Mac 터미널에서 `./scripts/deploy-release.sh`를 실행하면:
1. 무거운 Android Release 빌드(APK/AAB)는 고성능 Linux 서버(`192.168.0.154`)에 SSH로 위임하여 실행합니다 (Mac 부하 0%).
2. 빌드 완료된 산출물은 Mac 로컬 `dist/`로 자동 수거(`scp`)됩니다.
3. 버전 태그 푸시, GitHub Releases 배포, 오픈소스 레포 동기화가 순차적으로 자동 처리됩니다.

```bash
./scripts/deploy-release.sh [versionName] [versionCode] [releaseNotes]
# 예시 (릴리즈 노트는 다국어 지원을 위해 영문으로 작성):
./scripts/deploy-release.sh 1.5.64 90 "Performance improvements and bug fixes"
# (releaseNotes 인수를 생략하면 이전 태그 이후의 Git 커밋 히스토리로부터 영문 릴리즈 노트가 자동 생성됩니다)
# 산출물만 원격 서버에서 다시 가져올 때:
./scripts/deploy-release.sh --fetch
```
- **배포 아티팩트 보관 (`dist/`)**:
  - `dist/comics8-v{version}.apk`: 독립형 Release APK
  - `dist/comics8-v{version}.aab`: Google Play Console 전용 AAB (보안상 GitHub 미공개, 로컬 dist/ 보관)
  - **dist 보관 정책**: 빌드를 누적하지 않고 **최신 1개 버전만 유지** (새 버전 수거 시 이전 빌드 파일 자동 삭제)
- **GitHub Releases 자동 업로드**:
  - **Android APK**: `crudust/comics8` GitHub Releases에 `comics8-latest.apk`로 자동 업로드
  - **macOS Desktop / Windows Zip**: GitHub Actions (`release.yml`)가 태그 푸시를 감지해 자동 빌드 및 업로드
- **GitHub Releases 보관 정책**: 최신 2개 버전만 유지하고 이전 릴리즈는 자동 정리 (용량 최적화)

---

## 4. 프로젝트 구조 (Directory Structure)

```
Comics8/
├── core/                       # 공통 모듈 (JS 플러그인 엔진, 모델, 네트워크, 통합 동기화 엔진)
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── i18n/               # 다국어 리소스 (한/영/일/중)
│       ├── model/              # ToonItem, EpisodeItem, SyncModels, DualSpread 등
│       ├── network/            # ToonClient, DNS, 배치 다운로더
│       ├── source/             # ComicSource, JS 샌드박스, 로컬/네트워크(SMB/WebDAV) 파일 시스템
│       └── sync/               # BaseSyncManager, SyncStorageAdapter, SyncWire
├── app/                        # Android 모듈 (APK)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/comics8/monitor/
│       ├── ComicsApp.kt / MainActivity.kt
│       ├── data/               # Room DB, AndroidSyncManager, DownloadManager
│       └── ui/                 # Jetpack Compose UI
├── desktop/                    # Desktop 모듈 (macOS .app / Windows 포터블 zip)
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── Main.kt / DesktopVersion.kt
│       ├── data/               # SQLite JDBC, DesktopSyncManager, ToonRepository
│       └── ui/                 # Compose Desktop UI (Browse, Series, Reader, History)
├── server/                     # 클라우드 동기화 & 정기구독 프록시 백엔드 (FastAPI + PostgreSQL 18)
│   ├── app/                    # sync, billing, proxy, pubsub_worker, database, models
│   ├── docker-compose.yml      # postgres (5433) + backend (8905) 컨테이너
│   ├── schema.sql              # 9개 최적화 테이블 DDL
│   └── Dockerfile
├── dist/                       # 최종 배포 아티팩트 보관 (APK, Play Store AAB)
├── scripts/
│   ├── run-desktop.sh              # Mac에서 데스크톱 앱 직접 실행
│   ├── deploy-release.sh           # APK + AAB 릴리즈 빌드 및 배포
│   ├── sync-public-repo.sh         # 오픈소스 공개 레포지토리 동기화
│   └── deploy.env.example          # ~/.comics8-deploy.env 예시
├── knowledge/                  # 도메인/모듈별 지속적 사양 및 아키텍처 불변 규칙 (Internal Only)
│   ├── README.md               # 지식 인덱스 & Write Gate 가이드
│   ├── modules/                # 모듈별 경계/수명주기 계약
│   └── domains/                # 기능 도메인별 계약 (JS, Sync, Reader, Network 등)
├── build.gradle.kts
├── settings.gradle.kts
├── local.properties
└── AGENTS.md
```

---

## 5. Agent 작업 지침 및 주의사항 (Guidelines for Agents)

1. **빌드/테스트 전 환경변수 (Linux 서버 및 Mac 데스크톱 실행)**:
   - Mac에서 데스크톱 앱을 실행할 때는 JDK 17이 필요합니다 (`./scripts/run-desktop.sh`). Android APK/AAB 빌드는 Linux 서버에서 전담합니다.
2. **릴리즈 배포 실행 (Mac 오케스트레이션 -> Linux 빌드 위임)**:
   - Mac 터미널에서 `./scripts/deploy-release.sh`를 실행합니다.
   - 무거운 Android Release 빌드는 Linux 개발서버(`crudust@192.168.0.154`)에 위임되어 Mac에 부하를 주지 않으며, 완성된 APK와 AAB는 Mac 로컬 `dist/`로 자동 수거됩니다.
   - macOS 앱(`Comics8-mac.zip`)과 Windows 앱(`Comics8-win.zip`)은 GitHub Actions(`release.yml`)가 태그 푸시를 감지해 자동 빌드 및 업로드합니다.
3. **Room Entity 변경 시 주의**:
   - Room 엔티티나 스키마 수정 시 KSP 코드 생성이 필요하며, DB 버전 업그레이드 시 마이그레이션 전략(`fallbackToDestructiveMigration()` 여부 확인)을 확인해야 합니다.
4. **테스트 추가 및 검증**:
   - 파싱 로직 변경 시 `core/src/test/resources/` fixture로 `:core:test`를 통과시키세요. Android 모듈 테스트는 `:app:testDebugUnitTest`입니다.

---

## 6. Knowledge Routing (지식 라우팅 및 관리 가이드)

- `knowledge/README.md`를 Comics8의 모듈/도메인별 지속적 계약(Contract) 및 불변 규칙(Invariants)의 Canonical Index로 사용합니다.
- 작업(기능 구현, 버그 수정, 리팩토링, 코드 리뷰)을 시작하기 전, **해당 작업과 직접 관련된 모듈(`knowledge/modules/`) 및 도메인(`knowledge/domains/`) 지식 파일만 선별 로드**하여 컨텍스트 효율을 극대화하세요:
  - **JS 플러그인 / 샌드박스 작업**: `knowledge/domains/js_source_engine.md`
  - **동기화 / 클라우드 백엔드 연동**: `knowledge/domains/sync_protocol_wire.md`, `knowledge/modules/server_backend.md`
  - **로컬 / 네트워크(SMB, WebDAV) 라이브러리**: `knowledge/domains/local_network_library.md`
  - **뷰어 / 페이징 / 듀얼 스프레드**: `knowledge/domains/reader_paging_engine.md`
  - **다운로더 / 배치 이미지 처리**: `knowledge/domains/download_queue_domain.md`
  - **다국어 (i18n)**: `knowledge/domains/i18n_translation.md`
  - **빌드 / 패키징 / 배포 파이프라인**: `knowledge/modules/release_pipeline.md`
- **코드 우선 원칙**: 문서와 실제 코드가 상충할 경우 실제 코드를 따르고 문서와의 괴리(drift)를 보고하세요.

### 6.1. Knowledge Write Gate (지식 작성 및 수정 원칙)

- **기본적으로 지식 문서를 임의로 생성/수정하지 마십시오.** (Do not write knowledge by default)
- 다음 3가지 조건이 **모두 충족**될 때만 지식을 수정/추가합니다:
  1. **구현 완료**: 현재 코드로 검증 및 반영 완료됨 (계획 단계나 임시 상태 X).
  2. **지속적 불변 규칙**: 비자명한 모듈 간 경계, 데이터 소유권, 영속성/네트워크 프로토콜 계약.
  3. **지속적 가치**: 향후 에이전트/개발자가 코드를 전수 탐색하지 않고도 즉시 파악해야 할 핵심 사실.
- **기록 금지 사항**: UI 스타일/컬러/배치, 임시 디버깅 로그/해결 내러티브, 마이그레이션 히스토리, 단순 함수 사용법 등은 지식 문서에 기록하지 않습니다.
- **용량 제한 (Cap Policy)**: 파일당 Soft Cap 10,000자 / Hard Cap 16,000자를 엄격히 준수합니다.
- **비공개 원칙**: `knowledge/` 문서는 내부 개발용이며 공개 오픈소스 레포지토리(`crudust/comics8`)에는 동기화하지 않습니다.
