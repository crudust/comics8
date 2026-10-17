# Comics8 Agent Knowledge

This directory provides module-specific and domain-specific architectural invariants and durable contracts for Comics8 development.

---

## 1. Role & Authority (Read Policy)

- **Navigation Map, Not Ground Truth**: Use these documents as an orientation map to understand system boundaries, module ownership, and data flow.
- **Code is Ground Truth**: Documentation may lag behind recent code changes or omit fine-grained implementation details. Never treat knowledge files as infallible specifications—always inspect current code for definitive behavior and report drift.
- **Selective Reading**: Read only the 1–2 documents directly relevant to the current task before editing or answering questions. Do not preload unrelated knowledge files.

---

## 2. Document Standard (Write Policy)

Default: **do not write knowledge**. Code changes do not automatically require documentation updates.

Write or update only when all three conditions are met:
1. **Implemented now**: The behavior is verified and running in code (not planned, temporary, or WIP).
2. **Durable, non-obvious boundary or invariant**: It establishes a cross-module contract, data ownership boundary, persistence rule, or execution constraint.
3. **Future work needs the fact**: Future developers/agents need this fact beyond reading the owning code in a single file.

### Standard Structure for Knowledge Documents (3-Part Layout)

Every document in this directory must strictly follow this structure:
1. **`## 1. Scope & Canonical Owners`**: Core responsibility, canonical lifecycle/flow, and 2–4 key entry-point code files.
2. **`## 2. Core Invariants`**: 3–5 non-negotiable business, platform, security, or data invariants with numbered headings.
3. **`## 3. Cross-Boundary Contracts`**: Boundary handoffs and direct relative links to collaborating owner documents.

### Strictly Forbidden in Knowledge

- **Natural-Language Code Mirroring**: Do not transcribe function-level `if-else` branching, parameter dictionaries, or step-by-step algorithms into English (the code owns these).
- **Cross-Domain Duplication**: Do not re-explain rules owned by another document—link directly to the owner.
- **Obvious Engineering Trivia**: Do not restate basic Android/Compose concepts, standard REST practices, or general Kotlin idioms.
- **Change History & Narratives**: Do not record bug-fix stories, migration episodes, UI color palettes, or layout coordinates.

---

## 3. Maintenance & Scaling Policy

- **Healthy Target**: Aim for 3,000 – 7,000 characters per file (dense, factual, and invariant-focused).
- **Compression First**: When a document grows large, first prune implementation trivia (function branches, parameter lists), eliminate cross-domain duplication, and generalize obvious points.
- **Split over Information Loss**: If a document remains dense and large even after removing code trivia, do NOT delete essential business invariants or safety rules. Instead, **propose splitting the document** into focused sub-domains with clear independent ownership.
- **Health Check**: Run `python3 knowledge/check_limits.py` to monitor document sizes and identify candidates for pruning or splitting.
- **Confidentiality Invariant**: Knowledge documents are internal-only and are excluded from the public open-source repository (`crudust/comics8`).

---

## Canonical Index

### Modules (`modules/`)
*Cross-layer runtime boundaries, build/runtime lifecycles, and platform-specific entry points:*
- [modules/core_module.md](modules/core_module.md): Kotlin Multiplatform shared core, models, network interceptors, memory budget, and common interfaces.
- [modules/android_app.md](modules/android_app.md): Android Compose UI, Room 2.6.1 DB, SAF URI mirroring, and WorkManager background tasks.
- [modules/desktop_app.md](modules/desktop_app.md): Compose Desktop UI, SQLite JDBC storage, desktop windowing/hotkeys, and discrete scroll latching.
- [modules/server_backend.md](modules/server_backend.md): FastAPI + PostgreSQL 18 sync backend, `schema.sql` single authority, billing proxy, and pubsub worker.
- [modules/release_pipeline.md](modules/release_pipeline.md): Linux centralized release script, Mac SSH remote packaging, Windows portable zip, and Homebrew automation.

### Domains (`domains/`)
*Cross-platform functional engines, data protocols, and reading workflows:*
- [domains/js_source_engine.md](domains/js_source_engine.md): JS plugin sandbox, `HostApiV1` bridge, QuickJS/Rhino runtime, and plugin isolation.
- [domains/sync_protocol_wire.md](domains/sync_protocol_wire.md): BaseSyncManager, Wire protocol, Last-Write-Wins (LWW) resolution, and sync state flags.
- [domains/local_network_library.md](domains/local_network_library.md): CBZ/ZIP/Folder local storage, SMB/WebDAV protocols, NaturalSort, and thumbnail cache pipeline.
- [domains/reader_paging_engine.md](domains/reader_paging_engine.md): DualSpread (two-page) layout, webtoon vertical scroll, reading directions, and progress tracking.
- [domains/download_queue_domain.md](domains/download_queue_domain.md): Batch image downloader, retry/backoff policies, fallback DNS, and interceptors.
- [domains/i18n_translation.md](domains/i18n_translation.md): Multi-language fallback system (Korean, English, Japanese, Simplified/Traditional Chinese).
