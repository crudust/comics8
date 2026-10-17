# Server Backend Module Contract

## 1. Scope & Canonical Owners

- **Role**: FastAPI + PostgreSQL 18 기반 클라우드 동기화 백엔드로, 기기 페어링, 양방향 LWW 증분 동기화, 안티 블로킹 프록시, Google Play 인앱 결제/구독 검증 및 비동기 워커를 소유합니다.
- **Canonical Flow**: `Client (Android/Desktop) -> FastAPI Endpoints (/sync, /proxy, /billing) -> PostgreSQL 18 (schema.sql) / PubSub Worker`.
- **Key Entry Points**:
  - Main App & Schema: `server/app/main.py` & `server/schema.sql` & `server/docker-compose.yml`
  - Cloud Sync & Pairing: `server/app/sync.py`
  - Anti-Blocking Proxy: `server/app/proxy.py`
  - Google Play Billing: `server/app/billing.py`
  - Background Worker: `server/app/pubsub_worker.py`

## 2. Core Invariants

### 1. Database Schema Ground Truth (`schema.sql`)
- **Canonical Authority**: `server/schema.sql`이 신규 데이터베이스 부트스트랩의 유일한 정규 진실(Ground Truth)입니다.
- **Schema Topology**: PostgreSQL 18 기반 10개 최적화 테이블(사용자, 동기화 상태, 기기 페어링, 열람 이력, 즐겨찾기, 결제, 글로벌 크라우드소싱 카탈로그)로 구성됩니다.
- **Identifier Typing**: 기본키/외래키는 `TEXT` 타입을 사용하여 긴 SMB/WebDAV 경로 해시, 가변 UUID, Base64 인코딩 ID를 절삭 없이 수용합니다.
- **Port Mapping**: PostgreSQL 컨테이너는 호스트 루프백 포트 `5433` (컨테이너 5432)에 매핑되고, FastAPI 컨테이너는 포트 `8905`로 노출됩니다.

### 2. High-Throughput Sync & LWW Resolution
- **Batch UPSERT & Tombstone**: 클라이언트와의 전체/증분 동기화는 PostgreSQL 네이티브 배치 UPSERT(`ON CONFLICT DO UPDATE`)를 통해 원자적 Last-Write-Wins (LWW)로 충돌을 해결하며, 대량 툼스톤(삭제 마커)을 안정적으로 처리합니다.
- **Device Pairing**: 6자리 숫자 페어링 코드 또는 UUID 동기화 키 기반으로 간편하고 안전하게 새 기기를 연결합니다.

### 3. Anti-Blocking Proxy & Strict Security Perimeter
- **Access Authorization**: 프록시 호출은 유효한 동기화 계정의 마스터 키와 슬라이딩 윈도우 레이트 리밋을 통과해야 합니다.
- **SSRF & DNS Rebinding Defense**: 표준 HTTP/HTTPS 포트(80, 443)만 허용하며, 매 홉마다 공인 IP 주소 바인딩을 검증·고정합니다 (리다이렉트 발생 시에도 재검증).
- **Payload & Content Boundary**: 대상 콘텐츠 타입은 문서 및 데이터(`text/*`, `application/json`, `application/xml`)로 엄격히 제한되며, 단일 페이로드 상한(2MB)과 동시 요청 수 제한을 강제합니다.

### 4. Fail-Closed Billing Verification
- **Google Play Receipt Validation**: 구독 검증 및 승인은 Google Play Developer API와 직접 연동되며 영속화됩니다.
- **Fail-Closed Security**: Google 자격증명 누락 또는 인증 실패 시 항상 안전하게 닫힌 상태(Fail-Closed)를 유지하며 Pro 권한을 임의 부여하지 않습니다 (모의 응답은 테스트 픽스처 전용).

## 3. Cross-Boundary Contracts

- **Sync Wire Protocol**: 클라이언트와 서버 간의 LWW 타임스탬프, 페어링 DTO 및 삭제 툼스톤 계약은 [domains/sync_protocol_wire.md](../domains/sync_protocol_wire.md)를 따릅니다.
- **Android Client Sync**: Room DB 데이터 추출 및 서버 전송은 [modules/android_app.md](android_app.md)를 따릅니다.
- **Desktop Client Sync**: SQLite JDBC 데이터 추출 및 서버 전송은 [modules/desktop_app.md](desktop_app.md)를 따릅니다.
