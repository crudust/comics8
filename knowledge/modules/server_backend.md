# Server Backend Module Contract

## Architecture & Topology

- **Stack**: FastAPI (Python 3.12+), PostgreSQL 18, Docker Compose.
- **Topology & Ports**:
  - PostgreSQL Container: Available only from the Compose network and host loopback port `5433` (mapped from 5432).
  - FastAPI Container: Exposed on port `8905`.
  - Docker config: `server/docker-compose.yml`.
- **Database Authority (`server/schema.sql`)**:
  - `schema.sql` is the canonical ground truth for fresh database initialization.
  - Contains 9 optimized PostgreSQL 18 tables covering users, sync state, device pairing, reading history, favorites, and billing.
  - Primary/foreign keys use `TEXT` identifiers to handle unbounded network/SMB UUIDs and Base64 encoded work IDs without truncation.

## Key Service Boundaries

- **`app/sync.py`**:
  - Handles client device pairing (6-digit numeric codes or UUID sync keys).
  - High-throughput full / incremental bidirectional synchronization powered by PostgreSQL native batch UPSERT (`pg_insert ... ON CONFLICT DO UPDATE`) with atomic Last-Write-Wins (LWW) resolution and bulk tombstone management.
- **`app/proxy.py`**:
  - Server-side document/data reverse proxy for bypassing ISP blocking, Cloudflare challenges, and regional geo-restrictions on scraped web pages and APIs.
  - Access requires a master key backed by a materialized sync account with sliding-window rate limiting. Standard HTTP/HTTPS ports (80, 443) are resolved and pinned to public IP addresses for each hop to prevent SSRF and DNS rebinding; redirects repeat that validation. Content types are strictly constrained to documents and data (`text/*`, `application/json`, `application/xml`), with single payload caps (2MB) and bounded concurrency.
- **`app/billing.py`**:
  - Manages Google Play subscription verification, acknowledgement, persistence, and per-user Pro status queries.
  - Missing or failed Google credentials always fail closed; synthetic billing responses exist only in test fixtures.
- **`app/pubsub_worker.py`**:
  - Async worker handling background comic update crawls, notification push queues, and cache invalidation.
