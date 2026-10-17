# Download Queue Domain Contract

## 1. Scope & Canonical Owners

- **Role**: 오프라인 열람을 위한 전체 만화 시리즈 및 회차 단위 백그라운드 다운로드 대기열, 동시성 제어, 재시도/지수 백오프, 파일 무결성 마커 검증을 소유합니다.
- **Canonical Flow**: `User Download Action -> DownloadQueue -> ImageBatchDownload (Concurrency Limit & Interceptors) -> Storage File Writer -> Completion Marker (.complete)`.
- **Key Entry Points**:
  - Storage Hierarchy: `core/src/main/kotlin/com/comics8/model/DownloadLayout.kt`
  - Batch Downloader: `core/src/main/kotlin/com/comics8/network/ImageBatchDownload.kt`
  - Android Downloader: `app/src/main/kotlin/com/comics8/monitor/data/download/DownloadManager.kt`
  - Desktop Downloader: `desktop/src/main/kotlin/data/DesktopDownloadManager.kt`

## 2. Core Invariants

### 1. Deterministic Storage Layout & Completion Markers
- **Predictable Hierarchy**: 다운로드된 파일은 정규 경로 규칙을 따릅니다: `<DownloadDir>/<SourceId>/<ComicTitle>/<EpisodeTitle>/<PageIndex>.jpg`.
- **Atomic Completion Marker**: 회차 내의 모든 이미지 자산이 손상 없이 완전히 디스크에 기록된 후에만 `.complete` 마커 파일(또는 메타데이터)을 생성합니다. 미완료된 불완전 다운로드는 뷰어에서 완성된 것으로 취급되지 않습니다.

### 2. Parallel Chunk Downloading & Backoff Policy
- **Bounded Concurrency**: 과도한 연결로 인한 IP 차단을 방지하기 위해 동시 연결 수(기본 3~5개)를 제한합니다.
- **Exponential Backoff**: HTTP 429(Rate Limit) 및 일시적 서버 오류(5xx) 감지 시 지수 백오프(exponential backoff) 재시도를 수행합니다.
- **Header & Referer Injection**: 다운로드 요청 시 각 소스 플러그인이 요구하는 동적 Referer 및 User-Agent 헤더를 투명하게 주입합니다.

### 3. Platform Lifecycle Integration
- **Android Foreground Service**: Android 환경에서는 앱 백그라운드 전환 시 프로세스 종료로 인한 다운로드 중단을 방지하기 위해 Foreground Service / WorkManager 제약을 활용합니다.
- **Desktop Coroutine Queue**: Desktop 환경에서는 메인 UI 스레드를 차단하지 않는 비동기 코루틴 큐로 다운로드를 진행하고 실시간 진행도 UI 콜백을 통지합니다.

## 3. Cross-Boundary Contracts

- **Network Interceptors & DNS**: DoH DNS 및 이미지 클라이언트 타임아웃 규칙은 [modules/core_module.md](../modules/core_module.md)를 따릅니다.
- **JS Source Resolution**: 에피소드 내 이미지 URL 추출은 [domains/js_source_engine.md](js_source_engine.md)를 따릅니다.
- **Local Storage Scanner**: 다운로드 완료된 폴더의 로컬 라이브러리 자동 반영은 [domains/local_network_library.md](local_network_library.md)를 따릅니다.
