# Server Backend Module Contract

## Architecture & Topology

- **Stack**: FastAPI (Python 3.12+), PostgreSQL 16, Docker Compose.
- **Topology & Ports**:
  - PostgreSQL Container: Exposed on port `5433` (mapped from 5432).
  - FastAPI Container: Exposed on port `8905`.
  - Docker config: `server/docker-compose.yml`.
- **Database Authority (`server/schema.sql`)**:
  - `schema.sql` is the canonical ground truth for fresh database initialization.
  - Contains 10 optimized tables covering sync states, device pairings, history, favorites, billing, and proxy rate limits.
  - Database schema changes must update `schema.sql` and provide migration scripts in `server/`.

## Key Service Boundaries

- **`app/sync.py`**:
  - Handles client device pairing (6-digit numeric codes or UUID sync keys).
  - Full / incremental bidirectional synchronization for `history`, `favorites`, `bookmarks`, and `settings`.
- **`app/proxy.py`**:
  - Server-side image and API reverse proxy for bypassing ISP blocking, Cloudflare challenges, and regional geo-restrictions.
- **`app/billing.py`**:
  - Manages premium subscription validation, token quotas, and external payment webhooks.
- **`app/pubsub_worker.py`**:
  - Async worker handling background comic update crawls, notification push queues, and cache invalidation.
