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

### 3.1. APK 및 Windows 포터블 단독 빌드
정식 서명된 Release APK 및 Windows 무설치 포터블 Zip을 빌드할 때 사용합니다:
```bash
./gradlew assembleRelease :desktop:packageWindowsZip
```
- **출력 APK 경로**: `app/build/outputs/apk/release/app-release.apk`
- **출력 Windows Zip 경로**: `desktop/build/compose/binaries/main/Comics8-win.zip`

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
Linux에서 `./scripts/deploy-release.sh`를 실행하면 APK와 Windows Zip을 빌드하고, 버전을 올린 뒤 `crudust/comics8` 오픈소스 레포와 GitHub Releases에 자동 배포합니다:
```bash
./scripts/deploy-release.sh [versionName] [versionCode] [releaseNotes]
# 예시:
./scripts/deploy-release.sh 1.5.64 90 "성능 개선 및 안정화"
```
- **Android APK**: `crudust/comics8` GitHub Releases에 `comics8-latest.apk`로 자동 업로드
- **Windows Zip**: `Comics8-win.zip` 자동 업로드
- **macOS Desktop**: GitHub Actions (`macos-latest`) 또는 빌더 Mac을 통해 자동 빌드/업로드
- **Homebrew Cask**: `build/homebrew/comics8.rb` 자동 생성 (SHA256 포함)

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
├── server/                     # 클라우드 동기화 & 정기구독 프록시 백엔드 (FastAPI + PostgreSQL 16)
│   ├── app/                    # sync, billing, proxy, pubsub_worker, database, models
│   ├── docker-compose.yml      # postgres (5433) + backend (8905) 컨테이너
│   ├── schema.sql              # 10개 최적화 테이블 DDL
│   └── Dockerfile
├── scripts/
│   ├── build-mac-local.sh          # Mac에서 /Applications 첫 설치
│   ├── update-mac-app-remote.sh    # Linux→Mac SSH 빌드/설치/zip
│   ├── run-desktop.sh              # Mac에서 데스크톱 앱 직접 실행
│   ├── deploy-release.sh           # APK + Mac zip + Win zip 서버 배포
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
