# JS Source Engine Domain Contract

## 1. Scope & Canonical Owners

- **Role**: 사용자 및 번들 자바스크립트 플러그인을 격리된 샌드박스에서 실행하여 외부 웹툰/만화 사이트를 통일된 `ComicSource` 인터페이스로 변환·제공합니다.
- **Canonical Flow**: `JsPackStore (bundle/manifest) -> JsEngine / JsSandbox (QuickJS/Rhino) -> HostApiV1 (HTTP/Jsoup/Crypto) -> JsComicSource -> Core Domain Models`.
- **Key Entry Points**:
  - Engine & Sandbox: `core/src/main/kotlin/com/comics8/source/js/JsEngine.kt` & `core/src/main/kotlin/com/comics8/source/js/JsSandbox.kt`
  - Host Bridge: `core/src/main/kotlin/com/comics8/source/js/HostApiV1.kt`
  - Source Adapter: `core/src/main/kotlin/com/comics8/source/js/JsComicSource.kt`
  - Plugin Pack Store: `core/src/main/kotlin/com/comics8/source/js/JsPackStore.kt`

## 2. Core Invariants

### 1. Strict Sandbox & Host Isolation
- **Guarded Host Bridge**: 플러그인 JS 코드는 오직 `HostApiV1`을 통해서만 네트워크 및 호스트 유틸리티(HTTP GET/POST, Jsoup DOM 쿼리, 암호화 헬퍼 MD5/SHA-256/Base64/AES, JSON)에 접근할 수 있습니다.
- **Zero OS / Filesystem Access**: 리플렉션, OS 프로세스 실행, 임의 파일 시스템 순회는 샌드박스 수준에서 엄격히 차단됩니다.

### 2. Standardized ComicSource Export Contract
- **Exported Function Mapping**: 모든 JS 플러그인은 정규 인터페이스를 구현합니다:
  - `loadListing(catalogId, page)` -> `ListingPage`
  - `search(query)` -> `List<ToonItem>`
  - `suggest(query)` -> `List<SearchSuggestion>`
  - `loadEpisodes(item, page)` -> `EpisodePage`
  - `resolveImages(episode, item)` -> `List<String>` (이미지 URL 또는 데이터 URI)

### 3. Cancellation & Timeout Budgeting
- **CallBudget Execution**: 모든 JS 호출은 코루틴 관리하의 `runInterruptible`과 `CallBudget` 제한 시간 내에서 실행됩니다.
- **Cancellation vs Timeout Disambiguation**: 스케줄러에 의한 타임아웃 만료 시 `IllegalStateException("source call timed out")`을 발생시키며, 외부 부모 코루틴 취소 시에는 `CancellationException`을 그대로 보존·전파하여 타임아웃 오류로 왜곡되지 않도록 보호합니다.

### 4. Proprietary Plugin Perimeter & Sanitization
- **Open Source Sanitization**: 비공개 플러그인 스크립트와 관련 테스트 픽스처는 내부 개발용이며 공개 오픈소스 레포지토리(`crudust/comics8`)에 동기화되지 않습니다 (공개 예제 `peppercarrot.js`만 유지).

## 3. Cross-Boundary Contracts

- **Core Source Contract**: 공통 소스 인터페이스 및 도메인 모델 정의는 [modules/core_module.md](../modules/core_module.md)를 따릅니다.
- **Network Interceptors**: 이미지 호출 시 동적 Referer/UA 주입 및 DoH DNS는 [modules/core_module.md](../modules/core_module.md) 및 [domains/download_queue_domain.md](download_queue_domain.md)를 따릅니다.
- **Public Mirroring Filter**: 공개 배포 시 플러그인 필터링 규칙은 [modules/release_pipeline.md](../modules/release_pipeline.md)를 따릅니다.
