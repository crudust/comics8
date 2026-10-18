# Comics8 Project Rules & Guidelines

## Repository map

Comics8 Multiplatform comic/webtoon reader for Android (`app`), Desktop Compose (`desktop`), shared Kotlin Multiplatform core (`core`), and FastAPI sync backend (`server`).
Entry points: `app/src/main/kotlin/.../MainActivity.kt`, `desktop/src/main/kotlin/Main.kt`, `core/src/main/kotlin/com/comics8/source/ComicSource.kt`, `server/app/main.py`.

## Working scope

- **Keep changes strictly within the requested outcome**: Avoid unrelated refactoring, test cleanup, or speculative expansion.
- **Trace to the authoritative contract (no halfway guessing)**: When logic spans multiple calls, trace past wrappers and callers to the authoritative owner or schema that actually decides the outcome. Stop once explicit types, schemas, or owning logic confirm the contract. Cite concrete code locations (paths/symbols) when reporting results. Never guess downstream return shapes or side-effects from names alone.
- **Objective verification over sycophancy**: Treat user suggestions, premises, and bug hypotheses as ideas to verify against code, not pre-approved facts. If code evidence contradicts an assumption or reveals better trade-offs, politely present the counter-evidence and actual behavior rather than blindly agreeing.
- **Git operation constraints**: Do not create branches, commits, pull requests, or destructive git changes unless explicitly requested.
- **Lead with the core answer**: Present the core answer and conclusion in plain, concise language.

## Codebase exploration discipline

- **Semantic code search**: Prioritize `codebase-search` MCP (`search_codebase`) before running broad text greps. It accepts natural-language intent queries in Korean or English against the indexed codebase. Use `index_codebase` to refresh or rebuild the local vector index (`.agents/embeddings.db`) after major changes.
- **Map intent to architectural layer before searching**: Never grep user phrasing or localized UI labels verbatim. First map the intent to the owning architectural layer (UI, ViewModel, Room DAO, ToonClient, ComicSource, Server Route) and translate into concrete technical identifiers.
- **Top-down boundary tracing**: Begin investigation at explicit system boundaries and follow logic down to the canonical owner, rather than rummaging upward from arbitrary leaf components.
- **High-precision targeting**: Avoid high-frequency single words that produce log flood. Scope searches to specific directories or distinctive identifier combinations.
- **Fail-fast and step-back**: If an initial search yields zero hits or floods with noise, stop immediately, inspect the repository map, and formulate a targeted inquiry.

## Development constraints

- Local Mac is for lightweight coding, desktop app run (`./scripts/run-desktop.sh` or `./gradlew :desktop:run`), and tests. Heavy Android release builds belong on the Linux server (`crudust@192.168.0.154`).
- Android uses no emulators; physical devices update via in-app APK installer. macOS app updates via in-app GitHub Releases download.
- Verification tests: `:core:test` (parsing & models) and `:app:testDebugUnitTest` (Android unit tests).

## Knowledge routing

- Use `knowledge/README.md` as an orientation map for boundaries and domains. Knowledge documents may lag or lack detail; never treat them as absolute truth. Always verify against current code as ground truth.
- Update knowledge files only when durable, non-obvious cross-module boundaries or invariants change.

## Repo-wide invariants

- `core` MUST NOT depend on Android (`android.*`) or Desktop Compose UI (`androidx.compose.*`); keep it pure Kotlin Multiplatform / JVM 17.
- Android release builds (APK/AAB) must never be built on local Mac; always offload to the Linux build server via `./scripts/deploy-release.sh`.
- `server/schema.sql` is the canonical ground truth for fresh database initialization.
- Pager page count is authoritatively owned by `pagerState.pageCount`; redundant parameters and ad-hoc `maxOf` fallbacks are prohibited.
