# Sync Protocol & Wire Domain Contract

## 1. Scope & Canonical Owners

- **Role**: 멀티플랫폼 클라이언트(Android, Desktop)와 클라우드 백엔드(`server/app/sync.py`) 간의 양방향 상태 동기화, 기기 페어링, LWW 충돌 해결, 삭제 툼스톤 관리, 크라우드소싱 카탈로그 정규화를 소유합니다.
- **Canonical Flow**: `UI Event / Reader Progress -> SyncStorageAdapter (Local DB) -> BaseSyncManager (Debounce & Mutex) -> SyncWire (JSON Payload) -> Server /sync (PostgreSQL Batch UPSERT)`.
- **Key Entry Points**:
  - Wire DTOs & Models: `core/src/main/kotlin/com/comics8/sync/SyncWire.kt` & `SyncModels.kt`
  - Core Base Engine: `core/src/main/kotlin/com/comics8/sync/BaseSyncManager.kt` & `SyncStorageAdapter.kt`
  - Source Binding Resolver: `core/src/main/kotlin/com/comics8/sync/SourceBindingManager.kt`
  - Server Sync Endpoints: `server/app/sync.py` & `server/schema.sql`

## 2. Core Invariants

### 1. Last-Write-Wins (LWW) & Tombstone Integrity
- **Timestamp Authority**: 모든 동기화 엔티티는 `updatedAt` 밀리초 타임스탬프를 지니며, 로컬과 서버 간의 충돌은 항상 더 큰 `updatedAt`을 채택하여 원자적으로 해소합니다.
- **Tombstone Resurrection Defense**: 삭제된 항목은 `deletedAt` 툼스톤 타임스탬프를 전파하여 구버전 기기와의 동기화 시 삭제된 엔티티가 부활하는 문제를 방지합니다.

### 2. Debouncing & Exclusive Execution
- **Progress Debounce**: 뷰어의 읽기 진행도 변경 이벤트는 1.5초~3초 동안 디바운스된 후 서버 푸시를 트리거하여 불필요한 트래픽 폭증을 방지합니다.
- **Atomic Mutex**: `BaseSyncManager.exclusiveSync()`는 `AtomicBoolean` 뮤텍스를 통해 동시 다발적인 중복 동기화 패스의 실행을 엄격히 방지합니다.

### 3. Canonical Source Clustering (`/catalog/source/resolve`)
- **4-Tier Resolution Pipeline**: 임의의 클라이언트 JS 소스 ID는 4단계 파이프라인(호스트 매칭 O(1) -> 누적 Toon ID 교집합 15% 이상 및 도메인 자가 치유 -> 타이틀 시맨틱 폴백 -> 고립 신규 소스 생성)을 거쳐 서버의 정규 소스 클러스터로 매핑됩니다.
- **Local Source Binding**: 클라이언트는 최초 발견 시 `SourceBindingManager`를 통해 로컬에 매핑 결과를 바인딩하여 후속 배치 카탈로그 질의의 부하를 0으로 유지합니다.

### 4. Server Account Materialization & Credential Transport
- **Transactional Materialization**: 마스터 동기화 키 해석은 부수효과가 없습니다 (단순 읽기나 페어링 요청으로 `users` 행을 생성하지 않음). 유효한 동기화 엔티티 저장이나 검증된 구독 결제 시에만 원자적으로 유저 행이 실체화됩니다.
- **Immutable Sync Key**: 비어있지 않은 저장된 `sync_key`는 인증 도중 불변이며, 동일 `user_id`로 정규화되는 다른 원시 키는 재바인딩되지 않고 거부됩니다.
- **Secure Transport Perimeter**: 동기화 자격증명은 `Authorization: Bearer` 또는 `X-Sync-Key` 헤더로만 전송되며 URL 쿼리 스트링 전송은 금지됩니다. 6자리 페어링 코드는 원자적으로 1회만 소비됩니다.

### 5. Storage Adapter Abstraction
- **Adapter Invariant**: `BaseSyncManager`는 오직 `SyncStorageAdapter` 인터페이스만을 통해 로컬 저장소와 상호작용합니다.
- **Platform Bindings**: Android는 Room 기반의 `AndroidSyncManager`, Desktop은 SQLite JDBC 기반의 `DesktopSyncManager`가 각각의 스토리지 어댑터를 구현합니다.

## 3. Cross-Boundary Contracts

- **Server Backend Contracts**: PostgreSQL 18 배치 UPSERT 및 스키마 DDL은 [modules/server_backend.md](../modules/server_backend.md)를 따릅니다.
- **Android Persistence**: Room DB의 동기화 상태 플래그 및 마이그레이션은 [modules/android_app.md](../modules/android_app.md)를 따릅니다.
- **Desktop Persistence**: SQLite JDBC 동기화 어댑터 구현은 [modules/desktop_app.md](../modules/desktop_app.md)를 따릅니다.
- **Reader Progress Events**: 뷰어 진행도 이벤트 발생 및 타임스탬프 기록은 [domains/reader_paging_engine.md](reader_paging_engine.md)를 따릅니다.
