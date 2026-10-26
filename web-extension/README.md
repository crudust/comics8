# Comics8 Web Extension

Chrome (Manifest V3) comic reader for Comics8. It runs the **same JavaScript source plugins** as the
Android/Desktop apps, reads **ZIP/CBZ and WebDAV** libraries, renders chapters in a **dual-spread or
webtoon** viewer, and syncs reading progress with the Comics8 backend.

```sh
pnpm install
pnpm dev        # CRXJS dev server; load ./dist as an unpacked extension
pnpm build      # production build in ./dist
pnpm typecheck  # tsc --noEmit
pnpm check      # svelte-check (Svelte + TS diagnostics)
pnpm test       # vitest (pure logic)
pnpm test:state # vitest, Svelte runes store (`state-*.test.ts`)
pnpm test:views # vitest, DOM + client build of Svelte (component tests)
```

## Layout

```
manifest.json           MV3 manifest (source of truth for every entry point)
vite.config.ts          Svelte + Tailwind v4 + CRXJS
svelte.config.js        svelte-check / editor Svelte preprocessing
src/
  index.html            the reader surface (opened by the toolbar icon)
  main.ts               mounts ReaderApp.svelte
  ReaderApp.svelte      reader shell: top bar, stage, empty state
  app.css               Tailwind entry + global tokens
  background/
    service-worker.ts   MV3 worker: installs every handler at the top level
    action-handler.ts   chrome.action.onClicked: opens/focuses the reader tab
    fetch-handler.ts    FETCH_REQUEST: cross-origin fetch, base64/data URLs, header sanitising
    referer-rules.ts    SET_REFERER_RULE: Referer/User-Agent via declarativeNetRequest
  messaging.ts          page <-> worker protocol (FETCH_REQUEST / FETCH_RESPONSE)
  background-fetch.ts   page-side client wrapper (backgroundFetch, backgroundFetchBytes)
  sources/
    plugin-host.ts      createPluginHost(): the `host` polyfill, loadSource(), LoadedSource
    replay.ts           runs synchronous plugin code on top of async fetch
    dom.ts              host.parseHtml(): browser DOM with Jsoup-compatible text/URL semantics
    registry.ts         user-installed sources (chrome.storage.local)
    builtin/            generated reference plugin fixtures; BUILTIN_SOURCES is empty
    types.ts            ToonItem / EpisodeItem / PageInfo / SourceMeta
  storage/
    zip-provider.ts     ZIP/CBZ -> Blob object URLs, natural page order, close() revokes
    webdav-provider.ts  PROPFIND client, D:multistatus parsing, archive + image-directory sources
  sync/
    sync-client.ts      /sync, /sync/delta, /pair/request, /pair/confirm
    tasks.ts            debounced, single-flight push runner
  viewer/
    types.ts            PageImage / SpreadPair / ViewerMode / FitMode
    ReaderPane.svelte   1:1 port of desktop/.../reader/ReaderPane.kt (SCROLL / PAGE / DUAL)
    reader-pages.ts     buildSinglePageSlices / buildDualSpreads / resolveBoundaryDecision port
    viewer-state.svelte.ts  reactive state, Smart Wide Spread grouping, navigation
    DualSpreadReader.svelte paged one/two-page spread with fit modes
    WebtoonReader.svelte    virtualised continuous strip with auto-scroll
    ViewerControls.svelte   overlay control bar
    shortcuts.ts        keyboard bindings
  state/
    desktop-state.svelte.ts  1:1 port of DesktopUiState + DesktopViewModel (the reader's store)
  ui/
    PageJumpDialog.svelte    modal page jump (desktop/.../components/PageJumpDialog.kt)
    SyncDialog.svelte        cloud-sync modal (desktop/.../components/SyncDialog.kt)
  data/
    favorites-store.svelte.ts  starred series (chrome.storage.local)
    history-store.svelte.ts    resume row + per-chapter read set
  views/
    BrowsePane.svelte   port of desktop/.../browse/BrowsePane.kt
    SeriesPane.svelte   port of desktop/.../series/SeriesPane.kt
    HistoryPane.svelte  port of desktop/.../history/HistoryPane.kt
    BrowseView.svelte   source picker, catalog tabs, search, poster grid, pagination
    SeriesView.svelte   full-page series header, resume/first-chapter actions, episode list
    LibraryView.svelte  favorites and read-history tabs
    SettingsPane.svelte port of desktop/.../settings/ (hub + five sub-panes)
    display.ts          pure display rules (page labels, tags, dedupe) with unit tests
```

### Browse / Series views

Both take the navigation intent as a prop and own their own data loading, so the shell only routes:

```svelte
<BrowseView onSelectSeries={(item, sourceId) => /* open the series page */} />
<SeriesView
  item={series}
  sourceId={sourceId}
  onBack={() => /* back to browsing */}
  onSelectEpisode={(episode, item, sourceId) => /* open the reader */}
/>
```

Rules that the views deliberately do not break:

- **Episode totals state where the number came from.** `DesktopStore.totalEpisodesOf(key)` returns,
  in authority order, a total the plugin reported (`result.total ?? totalItems ?? itemCount ??
  totalEpisodes`, at result level or in `pageInfo`), the count of a verified page sweep
  (`catalogTotals`), or the page-shape estimate `lastPage * pageSize`. A paginated listing prints
  the page it is showing next to the number (`총 250화 (1 / 6 페이지)`), and the loaded page length is
  the only fallback. `core/.../EpisodeListingTotal.kt` keeps the panel's denominator real: the sweep
  still replaces every estimate it can measure.
- **Resume, not "continue blindly".** `historyStore.getProgress` supplies the chapter and 0-based
  page; the label is `12화 이어보기 (15/45p)`. The views never write progress — only the reader knows
  a chapter's page count.
- **Search is a mode, not a filter.** Submitting replaces the grid and clearing restores the catalog
  the user was on; `source.search(query)` returns `ToonItem[]` per `docs/PLUGIN_GUIDE.md` §5.1.

## Background service worker

The worker is the extension's only network egress and its only writer of header rules. Everything
else (sources, storage, sync) is ordinary page code talking to it through `chrome.runtime`.

### Toolbar icon

The action has no popup and there is no side panel: clicking the icon opens the reader as a full tab
(`src/index.html`). `src/background/action-handler.ts` focuses the reader tab when one is already
open and creates none otherwise, so repeated clicks never stack duplicates. The manifest keeps the
action down to `{ "default_title": "Comics8" }` — `onClicked` only fires when no `default_popup` is
set.

### `FETCH_REQUEST`

`src/background/fetch-handler.ts` performs one request with the extension's `host_permissions`, so
cross-origin HTML/JSON/images and hotlink-protected manga pages are readable without CORS.

```ts
const reply = await chrome.runtime.sendMessage({
  action: "FETCH_REQUEST",
  requestId: "optional-correlation-id",
  url: "https://manga.test/chapter/1",
  method: "GET",
  headers: { Referer: "https://manga.test/", "User-Agent": "Mozilla/5.0" },
  responseType: "text", // "text" | "json" | "base64" | "data-url"
});
```

The reply always has `{ ok, status, statusText, url, headers, body, text, data, error? }`; binary
requests additionally carry `bodyBase64` and `dataUrl`. Envelopes with a `requestId` are exactly what
`sendToBackground()` validates, and the extra `data` / `text` / `json` / `dataUrl` fields exist for
plain one-shot callers.

Rules that are easy to get wrong, and are therefore enforced here:

- **Forbidden headers are stripped, not forwarded.** `Referer`, `User-Agent`, `Host`, `Cookie`,
  `Content-Length`, `Origin`, `Sec-*` and `Proxy-*` are removed from `init.headers`; `credentials` is
  always `omit` so an extension fetch can never carry the user's session.
- **`Referer` / `User-Agent` still get sent** — via a DNR rule installed *before* the request (see
  below), because `fetch()` silently drops them.
- **Binaries stay binary.** The body is only decoded to base64/data URL when `responseType` asks for
  it, and `maxBytes` (64 MiB default) is checked against `Content-Length` before buffering.
- **Failures are values, not exceptions.** Invalid URLs, unsupported schemes, timeouts and transport
  errors resolve with `status: 0` and an `error` string, so callers never mistake a dead request for
  an empty body.

### `SET_REFERER_RULE`

`fetch()` cannot set `Referer` or `User-Agent`, so hotlink protection is bypassed the only supported
way: `chrome.declarativeNetRequest` `modifyHeaders` rules. `referer-rules.ts` owns the desired state.

```ts
await chrome.runtime.sendMessage({
  action: "SET_REFERER_RULE",
  domain: "img.manga.test", // or `domains: [...]` / `url` / a full URL
  referer: "https://manga.test/",
  userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
});

// remove one domain, or every rule
await chrome.runtime.sendMessage({ action: "SET_REFERER_RULE", domain: "img.manga.test", remove: true });
await chrome.runtime.sendMessage({ action: "SET_REFERER_RULE", clear: true });
```

Implementation notes that matter for correctness:

- Specs live in `chrome.storage.local` under `comics8.refererRules`, so they survive worker restarts;
  `reconcileRefererRules()` re-installs them on every startup and drops stale rules.
- Every mutation re-reads storage and rewrites **all** rules inside one serialized queue, so
  concurrent messages can never leave a half-applied rule set. Rule ids stay in `9000..9399`
  (`MAX_REFERER_RULES`), far below the dynamic-rule quota.
- `requestDomains` also matches sub-domains, and the condition covers every resource type, so the
  same rule fixes `<img>` loads and `fetch()` alike.
- CR/LF and invalid URLs are rejected: a header value must never be able to smuggle extra headers.

## Running plugins written for the native engine

`docs/PLUGIN_GUIDE.md` §1 says plugins are synchronous and that `host.fetch()` "blocks until
completion and returns data directly". A browser cannot block, so `plugin-host.ts` executes a method
repeatedly until a pass needs no new network data (`replay.ts`). The consequence is that **a plugin
written against the spec runs unmodified** — `examples/sources/peppercarrot.js` is the reference
fixture `tests/plugin-host.test.ts` runs verbatim:

```ts
import { loadSource } from "./src/sources";

const source = loadSource(peppercarrotScript);
const listing = await source.loadListing("KO", 1);          // ListingPage
const episodes = await source.loadEpisodes(listing.items[0], 1);
const images = await source.resolveImages(episodes.items[0], listing.items[0]); // string[]
```

Only the outer entry points are async; everything the plugin calls (`host.fetchText`,
`host.parseHtml`, `host.extractImages`, `host.parsePageInfo`, `host.match`, …) is synchronous, exactly
like the native host.

Costs and limits, all documented in `replay.ts`:

- one round trip per *dependency chain* (fetch a page → parse → derive next URL = 2 passes);
- `host.fetchAll` and loops of independent requests converge in a single pass;
- recordings are cached per `(source, method, args)`, so revisiting a chapter is free;
- `maxPasses` / `maxRequests` bound runaway plugins and fail loudly;
- CPU-bound infinite loops cannot be preempted (browser limitation), and non-deterministic plugin code
  (`Math.random()`, clock-dependent branches) defeats replay.

`PluginHost` also has a plain async surface (`fetchAsync`, `fetchTextAsync`, `isAccessibleAsync`) for
extension code that does not want replay.

## Local and network libraries

```ts
import { ZipProvider, WebDavProvider } from "./src/storage";

const cbz = await ZipProvider.open(bytes);        // Uint8Array | ArrayBuffer
cbz.getPages();                                   // [{ name, url, blob, mimeType, bytes }] in natural order
cbz.close();                                      // revokes every object URL

const dav = new WebDavProvider({ url: "https://dav.example/comics", username, password });
await dav.listDirectories("");                    // folder browser
await dav.listArchives("Series A");               // .zip / .cbz
const pages = await dav.openImageDirectory("Series A/ch1"); // loose images, ordered
const archive = await dav.download("Series A/ch1.cbz");     // -> ZipProvider.open()
```

Entry filtering (`__MACOSX`, dotfiles, image extensions) and natural page ordering mirror
`core/src/main/kotlin/source/local/ZipImageNames.kt` and `NaturalSort.kt`, which are the canonical
owners of those rules.

## Sync

```ts
import { SyncClient, SyncTaskRunner } from "./src/sync";

const sync = new SyncClient();
await sync.init();                                 // loads the key/server from chrome.storage.local

await sync.push({ favorites, history, readEpisodes, readerSettings });
await sync.pull();                                 // full snapshot
await sync.delta({ history: [entry] });            // incremental

const pin = await sync.requestPairingCode();       // 6-digit code, TTL 300s
await sync.confirmPairingCode(pin.code);           // on the second device

const runner = new SyncTaskRunner(sync, { collectUpdates: () => localSnapshot() });
runner.schedule();                                 // 2.5s debounce, single-flight
```

Notes that come straight from `server/app/sync.py`:

- auth is `Authorization: Bearer <key>` and/or `X-Sync-Key`; never a query parameter;
- pairing is `pair/request` (existing device) then `pair/confirm` (new device) — there is no bare
  `/pair` route. `pair()` chains them for one-step callers;
- `pair/request` requires an existing account, so `requestPairingCode()` pushes the local snapshot
  first by default (`pushBeforePairing: false` to skip);
- `POST /sync` upserts per row with last-write-wins on `savedAt` / `lastReadAt` / `readAt` /
  `updatedAt`, and rejects rows whose `sourceId` is `local`;
- payloads are capped at `MAX_SYNC_ITEMS_PER_REQUEST` (10,000) rows; the client refuses to send more.

## Regenerating the reference plugin fixture

`src/sources/builtin/` is generated from `examples/sources/*.js`. The generated modules are **test
fixtures**: `src/sources/builtin/index.ts` exports an empty `BUILTIN_SOURCES`, so the extension ships
no comic source at all and every installed `.js` plugin shows up in the source manager as removable.

```sh
pnpm sync:builtin          # regenerate
pnpm sync:builtin:check    # verify no drift (CI)
```

## Development

```sh
pnpm install
pnpm typecheck
pnpm check
pnpm test
pnpm test:state
pnpm test:views
pnpm build
```

`pnpm dev` runs the CRXJS dev server with HMR and writes an unpacked extension to `dist/`; reload it
from `chrome://extensions`. Tests run under Node with `linkedom` for HTML and `@xmldom/xmldom` for XML
(`tests/harness.ts`), plus a fake `chrome` for the worker contract
(`tests/background-service-worker.test.ts`); the extension itself uses the browser's native DOM and
APIs.
