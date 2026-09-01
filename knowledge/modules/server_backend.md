# Server Backend Module Contract

## Architecture & Topology

- **Stack**: FastAPI (Python 3.12+), PostgreSQL 18, Docker Compose.
- **Topology & Ports**:
  - PostgreSQL Container: Available only from the Compose network and host loopback port `5433` (mapped from 5432).
  - FastAPI Container: Exposed on port `8905`.
  - Docker config: `server/docker-compose.yml`.
- **Database Authority (`server/schema.sql`)**:
  - `schema.sql` is the canonical ground truth for fresh database initialization.
  - Contains 9 optimized tables covering users, sync state, device pairing, reading history, favorites, and billing.
  - Database schema changes must update `schema.sql` and provide migration scripts in `server/`.

## Key Service Boundaries

- **`app/sync.py`**:
  - Handles client device pairing (6-digit numeric codes or UUID sync keys).
  - Full / incremental bidirectional synchronization for `history`, `favorites`, `bookmarks`, and `settings`.
- **`app/proxy.py`**:
  - Server-side image and API reverse proxy for bypassing ISP blocking, Cloudflare challenges, and regional geo-restrictions.
  - Access requires a master key backed by a materialized sync account. Allowlisted names are resolved and pinned to public IP addresses for each hop; redirects repeat that validation. Concurrent requests and streamed response bytes are bounded server-side.
- **`app/billing.py`**:
  - Manages Google Play subscription verification, acknowledgement, persistence, and per-user Pro status queries.
  - Missing or failed Google credentials always fail closed; synthetic billing responses exist only in test fixtures.
- **`app/pubsub_worker.py`**:
  - Async worker handling background comic update crawls, notification push queues, and cache invalidation.
