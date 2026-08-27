# Comics8 Agent Knowledge

This directory stores module-specific and domain-specific architectural invariants and durable contracts for Comics8 development.

## Default Policy: Do Not Write Knowledge

Code changes do not automatically require documentation updates.

Write or update knowledge **ONLY** when all three conditions are met:
1. **Implemented now**: The behavior is verified and running in code (not planned, temporary, or WIP).
2. **Durable invariant / boundary / contract**: It establishes a non-obvious cross-module contract, data ownership boundary, persistence rule, or execution constraint.
3. **Future value beyond reading code**: Future agents/developers need this fact quickly without reverse-engineering across multiple repository layers.

### Do Not Record:
- UI layout/styling/color codes/theme variables
- Temporary bug narratives or debugging history
- Step-by-step task logs or historical migration notes
- Volatile implementation trivia easily found in a single function

Put each accepted fact in exactly **one owner file**, replace stale text instead of appending logs, and link directly to source files.

---

## Canonical Index

### Modules (`modules/`)
Cross-layer runtime boundaries, build/runtime lifecycles, and platform-specific entry points.

- `modules/core_module.md`: Kotlin multiplatform shared core, models, network interceptors, and common interfaces.
- `modules/android_app.md`: Android Compose UI, Room 2.6.1 DB, SAF (Storage Access Framework) URI mirroring, and WorkManager contracts.
- `modules/desktop_app.md`: Compose Desktop UI, SQLite JDBC storage, desktop windowing/hotkeys, and folder pickers.
- `modules/server_backend.md`: FastAPI + PostgreSQL 18 sync backend, `schema.sql` authority, billing proxy, and pubsub worker.
- `modules/release_pipeline.md`: Linux centralized release script, Mac SSH remote packaging, Windows portable zip, and Homebrew cask automation.

### Domains (`domains/`)
Business logic contracts, data exchange protocols, and cross-platform functional engines.

- `domains/js_source_engine.md`: JS plugin sandbox, `HostApiV1` bridge, QuickJS/Rhino runtime, and plugin isolation.
- `domains/sync_protocol_wire.md`: BaseSyncManager, Wire protocol, Last-Write-Wins (LWW) resolution, and sync state flags.
- `domains/local_network_library.md`: CBZ/ZIP/Folder local storage, SMB/WebDAV protocols, NaturalSort, and thumbnail cache pipeline.
- `domains/reader_paging_engine.md`: DualSpread (two-page) layout, webtoon vertical scroll, reading directions, and progress tracking.
- `domains/download_queue_domain.md`: Batch image downloader, retry/backoff policies, fallback DNS, and interceptors.
- `domains/i18n_translation.md`: Multi-language fallback system (Korean, English, Japanese, Simplified/Traditional Chinese).

---

## Compression Policy

- **Soft cap**: 10,000 Unicode characters per Markdown file.
- **Hard cap**: 16,000 Unicode characters per Markdown file (strict limit).
- Caps are guardrails against context bloat. Files must remain dense, factual, and invariant-focused.
- When approaching the soft cap, compress by removing obsolete details, consolidating examples, and removing narrative fluff.
