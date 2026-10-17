# Local & Network Library Domain Contract

## 1. Scope & Canonical Owners

- **Role**: 로컬 저장소(CBZ, ZIP, 이미지 폴더) 및 원격 네트워크 공유(SMB v2/v3, WebDAV) 만화의 스캔, 인덱싱, 커버 썸네일 추출, 내추럴 정렬, 무압축 스트리밍을 소유합니다.
- **Canonical Flow**: `LocalFolder / SMB / WebDAV -> NaturalSort (alphanumeric order) -> CoverThumbCache (FileRevision invalidation) -> Zero-Extraction Stream -> Reader Bitmap Decode`.
- **Key Entry Points**:
  - Local Archive Reader: `core/src/main/kotlin/com/comics8/source/local/`
  - SMB / WebDAV Network Clients: `core/src/main/kotlin/com/comics8/source/network/`
  - Natural Ordering: `core/src/main/kotlin/com/comics8/model/NaturalSort.kt` & `EpisodeSortOrder.kt`
  - Thumbnail Cache & Revision: `core/src/main/kotlin/com/comics8/source/local/CoverThumbCache.kt`

## 2. Core Invariants

### 1. Zero-Extraction Archive Streaming
- **On-Demand Random Access**: ZIP 및 CBZ 아카이브는 디스크에 전체 압축을 풀지 않고(`Zero-Extraction`), 랜덤 액세스 스트림을 통해 이미지를 온디맨드로 직접 비트맵 메모리에 로드합니다.
- **Revision-Keyed Sessions**: ZIP 세션은 `source + path + FileRevision`으로 키가 지정되어 파일 변경 시 세션이 안전하게 갱신됩니다.

### 2. Natural Sorting & Deterministic Ordering
- **Alphanumeric Natural Sort**: 파일명과 회차명(예: `1.jpg`, `2.jpg`, `10.jpg`)은 단순 사전순이 아닌 자연수 인식 정렬(`NaturalSort`)을 적용하여 `10`이 `1` 바로 뒤에 배치되는 문제를 원천 차단합니다.
- **Configurable Episode Sort Order**: `EpisodeSortOrder`(이름 오름/내림차순, 수정일 내림/오름차순) 적용 시에도 동점(tie-break) 상황에서는 자연 정렬 순서를 엄격히 유지합니다.

### 3. Cover Thumbnail Pipeline & Revision Cache Invalidation
- **First Valid Image Discovery**: 폴더/아카이브 내의 첫 번째 유효 이미지를 식별하여 대표 커버 썸네일로 생성합니다.
- **Path + Revision Hashing**: 썸네일 캐시는 경로와 `FileRevision` 해시를 결합하여, 원본 파일의 크기 변화나 교체 시 성공 및 실패 캐시 엔트리가 모두 즉시 무효화되도록 보장합니다.

### 4. Network Storage Resilience (SMB / WebDAV)
- **Safe Network Range Reads**: WebDAV 범위 읽기(`Range`)는 ETag 또는 Last-Modified 전제조건을 검증하여 서로 다른 파일 리비전의 바이트가 섞여 디코딩되는 결함을 방지합니다.
- **Non-Destructive Offline Resilience**: 네트워크 단절이나 인증 만료가 발생해도 기존 인덱싱된 라이브러리 카탈로그를 삭제하지 않고 오프라인 인디케이터로 우아하게 표시합니다.

## 3. Cross-Boundary Contracts

- **Android Scoped Storage**: Android 11+ 환경의 SAF URI 권한 지속성은 [modules/android_app.md](../modules/android_app.md)를 따릅니다.
- **Desktop Native Picker**: 데스크톱 시스템 디렉토리 선택 및 권한은 [modules/desktop_app.md](../modules/desktop_app.md)를 따릅니다.
- **Core Source Contract**: 공통 `ComicSource` 구현 및 정렬 변환은 [modules/core_module.md](../modules/core_module.md)를 따릅니다.
- **Reader Rendering**: 압축 해제 스트림의 뷰어 렌더링 및 듀얼 스프레드 처리는 [domains/reader_paging_engine.md](reader_paging_engine.md)를 따릅니다.
