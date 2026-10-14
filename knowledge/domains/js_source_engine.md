# JS Source Engine Domain Contract

## Responsibilities & Architecture

The JS Source Engine runs user and bundled JavaScript plugin scripts within an isolated sandbox environment, mapping external web comic sites into uniform `ComicSource` interfaces.

## Key Components (`core/src/main/kotlin/source/js/`)

- **`JsEngine` / `JsSandbox`**:
  - Embedded JavaScript runtime (QuickJS / Rhino fallback).
  - Executes plugin code in isolated contexts without direct OS or filesystem access.
- **`HostApiV1` & `HostObject`**:
  - Injected bridge object providing controlled host capabilities:
    - HTTP requests (`http.get()`, `http.post()`, header injection, cookie handling).
    - HTML DOM parsing (Jsoup-backed query selectors).
    - Crypto utilities (MD5, SHA-256, Base64, AES decryption helpers).
    - JSON serializer / deserializer.
- **`JsPackStore`**:
  - Manages installed plugin bundles, version updates, metadata manifests, and source configuration toggles.
- **`JsComicSource`**:
  - Kotlin adapter implementing `ComicSource` by calling JavaScript exported functions:
    - `loadListing(catalogId, page)` -> `ListingPage`
    - `search(query)` -> `List<ToonItem>`
    - `suggest(query)` -> `List<SearchSuggestion>`
    - `loadEpisodes(item, page)` -> `EpisodePage`
    - `resolveImages(episode, item)` -> `List<String>` (Image URLs or data URIs)

## Durable Invariants

1. **Host Isolation**: Plugin JS code MUST only access the network and host features via `HostApiV1`. Direct reflection, process execution, or file path traversal is strictly prohibited.
2. **Cancellation & Timeout**: Every JS invocation must execute under coroutine-managed timeouts (`runInterruptible`) with a `CallBudget`. Timeouts (triggered by scheduler) must throw `IllegalStateException("source call timed out")`, while external coroutine cancellations must strictly preserve and rethrow `CancellationException` rather than being converted into timeout errors.
3. **Public Repo Sanitization**: Proprietary or non-open-source JS plugin scripts (e.g. `hitomi.js`, `eleven.js`, `rawkuma.js`) and their tests are internal and MUST NOT be synced to the public repository.
