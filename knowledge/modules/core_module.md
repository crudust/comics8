# Core Module Contract

## 1. Scope & Canonical Owners

- **Role**: Kotlin Multiplatform / JVM 17 기반 공통 코어로, 플랫폼 비의존적인 공유 도메인 모델, 만화 소스 인터페이스, 네트워크/이미지 인터셉터, 백업 직렬화, 순수 도메인 변환 및 메모리 분배 정책을 소유합니다.
- **Canonical Flow**: `ComicSource (loadListing / search / loadEpisodes / resolveImages) -> ToonClient / Interceptors (DoH DNS, Referer, Cache Bypass) -> RepositoryTransforms / Domain Logic -> Platform Consumers (Android Room / Desktop SQLite)`.
- **Key Entry Points**:
  - Domain Models & Source Contract: `core/src/main/kotlin/com/comics8/model/` & `core/src/main/kotlin/com/comics8/source/ComicSource.kt`
  - Network & DNS: `core/src/main/kotlin/com/comics8/network/ToonClient.kt` & `core/src/main/kotlin/com/comics8/network/FallbackDns.kt`
  - Image Interceptor: `core/src/main/kotlin/com/comics8/network/ComicImageInterceptor.kt`
  - Pure Transforms & Serialization: `core/src/main/kotlin/com/comics8/model/RepositoryTransforms.kt` & `core/src/main/kotlin/com/comics8/model/BackupWireCodec.kt`
  - Image Memory Policy: `core/src/main/kotlin/com/comics8/image/ImageMemoryPolicy.kt`

## 2. Core Invariants

### 1. Zero UI/Platform Dependency Perimeter
- **Agnostic Architecture**: `core`는 Android 프레임워크(`android.*`) 및 Desktop Compose UI(`androidx.compose.*`)에 절대 의존하지 않습니다.
- **Headless & Multiplatform Ready**: 순수 Kotlin JVM 17 컴파일과 헤드리스 단위 테스트(`:core:test`)를 보장하여 플랫폼 변경에 영향받지 않습니다.

### 2. ComicSource Interface & Non-Blocking Execution
- **Unified Source Contract**: JS 플러그인, 로컬 CBZ/ZIP, SMB, WebDAV를 포함한 모든 만화 소스는 `ComicSource` 인터페이스를 구현합니다.
- **Suspend & Threading Invariant**: `loadListing()`, `search()`, `suggest()`, `loadEpisodes()`, `resolveImages()` 등 I/O 및 장시간 연산은 호출 측 스레드를 블로킹하지 않으며 `Dispatchers.IO` / `runInterruptible` 환경에서 안전하게 취소 가능해야 합니다.

### 3. Network Resilience & Image Interceptors
- **Resilient DNS Fallback**: `FallbackDns`는 기본 시스템 DNS 실패 시 Cloudflare/Google DoH(DNS-over-HTTPS)로 투명하게 전환합니다.
- **Referer & Cancellation Guard**: `ComicImageInterceptor`는 소스 도메인 오리진에 맞춘 Referer/UA를 주입하며, 요청 취소(`isCanceled()`)를 감지하고 HTTP 404/5xx 또는 복구 불가능한 호스트 에러 시에만 폴백을 제한하여 유효한 호스트에서의 불필요한 타임아웃 캐스케이드를 차단합니다.
- **Image HTTP Client Separation**: `ToonClient.newImageHttpClient`는 상위 레이어(Coil / Desktop 디스크 캐시)의 단일 소스 원칙을 위해 OkHttp 자체 디스크 캐시를 해제(`cache(null)`)하고, 모바일 핸드셰이크를 고려한 15초 타임아웃 및 호스트당 동시성 4 제한을 적용합니다.

### 4. Cross-Platform Data Boundaries & Transformers
- **Wire Codec Authority**: `BackupWireCodec`이 로컬 백업 JSON v2 와이어 포맷 및 레거시 호환 디코딩을 전담하며, Android Room과 Desktop SQLite는 영속성 레코드를 와이어 DTO로 변환하는 역할만 수행합니다.
- **Pure Repository Transforms**: `RepositoryTransforms`가 정렬, 회차 수 보정, 리스팅 플래그, 저장된 뷰어 설정 파싱 등 순수 계산을 소유하며, 플랫폼 레포지토리는 DB/파일시스템/코루틴 수명주기만 담당합니다.

### 5. Classified Image Memory Budget
- **Memory Classification**: 모든 디코딩 UI 이미지는 `GRID`, `EPISODE`, `READER`로 엄격히 분류되며, 엔트리 개수가 아닌 디코딩 바이트 용량 기준으로 제한됩니다.
- **Partition Ratio**: 각 플랫폼별 총 예산 중 `GRID 32%`, `EPISODE 5%`, `READER 63%` 비율로 할당됩니다.
- **Lifecycle Invalidation**: `GRID`는 뷰어 이동 시에도 유지되고, `EPISODE`는 자체 소형 LRU를 사용하며, `READER`는 회차 변경 및 뷰어 종료 시 즉시 비워지고 진행 중인 이미지 로딩 코루틴을 취소합니다 (인코딩 디스크 캐시는 유지).

## 3. Cross-Boundary Contracts

- **Android Integration**: Room DB 매핑 및 Coil 파티션 적용은 [modules/android_app.md](android_app.md)를 따릅니다.
- **Desktop Integration**: SQLite JDBC 매핑 및 Skia 메모리 적용은 [modules/desktop_app.md](desktop_app.md)를 따릅니다.
- **JS Plugins**: 플러그인 샌드박스 런타임 및 브리지는 [domains/js_source_engine.md](../domains/js_source_engine.md)를 따릅니다.
- **Reader Mechanics**: 뷰어 페이징 및 듀얼 스프레드 순수 계산은 [domains/reader_paging_engine.md](../domains/reader_paging_engine.md)를 따릅니다.
- **Download Batching**: 청크 다운로드 및 대기열 관리는 [domains/download_queue_domain.md](../domains/download_queue_domain.md)를 따릅니다.
