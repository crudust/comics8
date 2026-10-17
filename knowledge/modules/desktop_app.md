# Desktop App Module Contract

## 1. Scope & Canonical Owners

- **Role**: Compose for Desktop (macOS / Windows, JVM 17) 기반 데스크톱 클라이언트 모듈로, 데스크톱 전용 윈도우 관리, SQLite JDBC 로컬 영속성, 데스크톱 키보드/트랙패드 제스처 래칭, 네이티브 폴더 피커, 데스크톱 이미지 로더 및 인앱 업데이트를 소유합니다.
- **Canonical Flow**: `Main.kt -> DesktopWindow -> DesktopNavigation / ViewModels -> DesktopToonRepository -> SQLite JDBC / DesktopSyncManager -> Core Models & Sources`.
- **Key Entry Points**:
  - Main Entry & Windowing: `desktop/src/main/kotlin/Main.kt` & `desktop/src/main/kotlin/DesktopVersion.kt`
  - Persistence & SQLite: `desktop/src/main/kotlin/data/DesktopDatabase.kt` & `desktop/src/main/kotlin/data/DesktopSyncManager.kt`
  - Gesture & Discrete Scroll Paging: `desktop/src/main/kotlin/ui/components/` & `desktop/src/main/kotlin/ui/reader/`
  - Native Integration: `desktop/src/main/kotlin/data/DesktopFolderPicker.kt` & `desktop/src/main/kotlin/data/DesktopUpdateManager.kt`

## 2. Core Invariants

### 1. SQLite JDBC & SyncStorageAdapter Contract
- **Direct JDBC Persistence**: `DesktopDatabase`는 가벼운 SQLite JDBC 커넥션을 직접 운용하며, 스키마 정의와 인덱스를 자체 관리합니다.
- **SyncStorageAdapter Implementation**: Android의 Room과 동일한 동기화 프로토콜을 보장하기 위해 `SyncStorageAdapter`를 엄격히 구현하여 열람 이력, 즐겨찾기, 뷰어 설정의 일관성을 유지합니다.

### 2. Discrete Scroll Paging & Gesture Latching
- **Single Trigger per Physical Gesture**: 트랙패드 연속 스와이프나 마우스 휠 이벤트 스트림은 `desktopDiscreteScrollPaging`에 의해 단일 이산(discrete) 페이지 전환으로 래칭됩니다.
- **Cooldown & Momentum Decay**: 물리 제스처마다 강제 쿨다운(`SCROLL_LATCH_COOLDOWN_MS`)과 관성 감쇠(`isScrollIdle`)를 검증하여 급격한 휠 조작 시 페이지가 연쇄적으로 넘어가는 현상을 방지합니다.
- **Top-Edge Arming for Pull-to-Refresh**: 상단 당겨서 새로고침 제스처는 스크롤 스트림이 이미 맨 위(`isAtTop()`)에 위치한 상태에서 시작될 때만 무장(arm)되며, 고속 상향 스크롤 중의 잔여 관성으로 인한 오작동을 원천 차단합니다.
- **Root Level Container Hosting**: 자식 컴포넌트 마운트/언마운트 시 래치 상태가 초기화되지 않도록 제스처 인식기는 최상위 루트 컨테이너에 상주해야 합니다.

### 3. Native Integration & In-App Updating
- **Native Folder Selection**: 데스크톱 네이티브 파일 다이얼로그(`DesktopFolderPicker`)를 통해 로컬 라이브러리 디렉토리를 즉각적으로 추가합니다.
- **Direct GitHub Releases Update**: macOS `.app` 및 Windows portable zip은 `DesktopUpdateManager`를 통해 GitHub Releases 최신 버전을 감지하고 인앱에서 직접 다운로드받아 갱신합니다.

## 3. Cross-Boundary Contracts

- **Core Module Contracts**: 도메인 모델, 이미지 메모리 파티션(Skia 캐시), 순수 변환 로직은 [modules/core_module.md](core_module.md)를 따릅니다.
- **Reader Engine Contract**: 페이징 모드(WEBTOON, PAGED_LTR, PAGED_RTL) 및 듀얼 스프레드 순수 계산은 [domains/reader_paging_engine.md](../domains/reader_paging_engine.md)를 따릅니다.
- **Cloud Sync Wire**: DesktopSyncManager와 동기화 프로토콜은 [domains/sync_protocol_wire.md](../domains/sync_protocol_wire.md)를 따릅니다.
- **Local CBZ / Storage**: 로컬 파일 시스템 탐색 및 CBZ/ZIP 압축 해제는 [domains/local_network_library.md](../domains/local_network_library.md)를 따릅니다.
- **CI/CD Desktop Packaging**: GitHub Actions (`release.yml`)의 macOS/Windows 데스크톱 빌드 파이프라인은 [modules/release_pipeline.md](release_pipeline.md)를 따릅니다.
