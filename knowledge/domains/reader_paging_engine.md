# Reader & Paging Engine Domain Contract

## 1. Scope & Canonical Owners

- **Role**: 멀티플랫폼(Android, Desktop) 만화/웹툰 뷰어의 캔버스 렌더링, 페이지 가상화, 듀얼 스프레드(2페이지 펼침) 계산, 읽기 방향 제어, 제스처 래칭 및 읽기 진행도 동기화를 소유합니다.
- **Canonical Flow**: `User Gesture / Keypress -> Latch / PagerState -> ReaderDomain (pure layout calculation) -> Viewport Settlement -> ReaderProgress -> SyncManager`.
- **Key Entry Points**:
  - Core Pure Logic & Dimensions: `core/src/main/kotlin/com/comics8/model/DualSpread.kt` & `ReaderDomain.kt`
  - Reading Progress Events: `core/src/main/kotlin/com/comics8/model/ReaderProgress.kt`
  - Android Reader UI: `app/src/main/kotlin/com/comics8/monitor/ui/reader/`
  - Desktop Reader UI & Latch: `desktop/src/main/kotlin/ui/reader/`

## 2. Core Invariants

### 1. Unified Reading Modes & Scroll Directions
- **Reading Modes**:
  - `WEBTOON` (세로 연속 스크롤): 사전 로딩(Preloading)을 동반한 끊김 없는 세로 이미지 스트립.
  - `PAGED_LTR` (좌->우 페이지 넘김): 서구형 표준 만화 방향.
  - `PAGED_RTL` (우->좌 페이지 넘김): 동양권 출판 만화(Manga) 방향.

### 2. Dual Spread Layout Calculation
- **Orientation & Dimensions**: 가로 모드 또는 와이드스크린 디스플레이에서 세로형 이미지 2장을 단일 뷰포트에 나란히 결합하여 2페이지 펼침을 구성합니다.
- **Aspect Ratio Bypass**: 원본 이미지가 이미 와이드 가로 이미지(펼침 양면 페이지)인 경우 분할/결합을 건너뛰고 전체 너비로 렌더링합니다.
- **Odd/Even Pairing Offset**: 챕터 커버 페이지 정렬을 위해 홀수/짝수 페어링을 1페이지씩 시프트할 수 있는 오프셋 옵션을 지원합니다.

### 3. Pure Reader Decisions vs Platform ViewModels
- **Pure Core Ownership**: 1-based 페이지 클램핑, 회차 순서 인식(`isAscending` 준수 `nextEpisode`/`prevEpisode`), 초기 이미지 위치, 종횡비 검증, 차원 기반 듀얼 모드 선택, 스크롤 방향 결정(`resolveScrollDirection`)은 `core`의 순수 함수(`ReaderDomain.kt`)가 전담합니다.
- **Platform Separation**: 플랫폼 ViewModel은 수명주기, 로컬 영속성, UI 상태 보관 및 바인딩만 소유합니다.

### 4. PagerState PageCount Single Authority & Gesture Latching
- **Sole Authority**: `pagerState.pageCount`가 뷰어 전체의 유일한 정규 페이지 수입니다. 중복된 `totalPages` 파라미터나 임의의 `maxOf` 폴백 연산은 엄격히 금지됩니다.
- **Discrete Scroll Paging Latch**: 데스크톱의 연속 트랙패드/마우스 휠 스트림은 `desktopDiscreteScrollPaging`을 통해 단일 페이지 넘김으로 래칭됩니다.
- **Gesture Contract**: 단일 물리 제스처당 1회 트리거, 강제 쿨다운(`SCROLL_LATCH_COOLDOWN_MS`), 관성 감쇠 검증(`isScrollIdle`), 최상단 상태에서만 새로고침을 무장하는 `isAtTop()` 조건을 만족해야 하며, 래치 상태는 뷰의 루트 컨테이너에 상주해야 합니다.
- **Stream Ownership**: 스크롤 이벤트는 `PointerEventPass.Initial`에서 관찰해 페이지 넘김/새로고침으로 확정된 이벤트를 내부 스크롤러보다 먼저 소비합니다(스크롤과 페이지 넘김 동시 적용 금지). 또한 `resolveScrollGestureAxis`로 스트림의 우세 축을 1회 고정하며, 세로 우세 스트림은 `allowVerticalWheel=false` 컨테이너에서 절대 페이지를 넘기지 않습니다.
- **Pull Direction**: 새로고침은 최상단에서 시작된 **위쪽(음수 deltaY)** 드래그에서만 발동합니다(`isPullRefreshGesture`). 최상단에서의 아래쪽 스크롤은 목록 스크롤이며, 페이지 넘김/새로고침으로 해석해서는 안 됩니다.

### 5. Progress Synchronization Settlement
- **Settlement Emission**: 정확한 스크롤 오프셋과 페이지 번호는 페이지 전환이 완료되고 뷰포트가 안착(settlement)된 시점에만 기록됩니다.
- **Monotonic Progress Advance**: 읽기 진행도가 전진하거나 사용자가 명시적으로 읽기를 일시정지할 때만 `SyncManager`로 이벤트를 발행하며, 마지막 페이지 임계값 도달 시 완료 상태(`finished`)로 마킹합니다.

## 3. Cross-Boundary Contracts

- **Sync Wire Protocol**: 뷰어 진행도 이벤트의 디바운스 및 LWW 동기화는 [domains/sync_protocol_wire.md](sync_protocol_wire.md)를 따릅니다.
- **Image Memory Budget**: 뷰어 종료 및 회차 전환 시 `READER` 캐시 해제와 코루틴 취소는 [modules/core_module.md](../modules/core_module.md)를 따릅니다.
- **Desktop Window & Hotkeys**: 데스크톱 키보드 단축키 및 창 제어는 [modules/desktop_app.md](../modules/desktop_app.md)를 따릅니다.
- **Android Immersive Mode**: 안드로이드 전체화면 몰입 모드 및 시스템 바 처리는 [modules/android_app.md](../modules/android_app.md)를 따릅니다.
