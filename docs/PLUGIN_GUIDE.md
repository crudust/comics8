# Comics8 Source Plugin Specification

Authoritative technical specification for authoring Comics8 JavaScript source plugins.

---

## 1. Execution Contract & Environment

- **Engine**: Mozilla Rhino in ECMAScript 6 mode (`VERSION_ES6`). Supports `const`, `let`, arrow functions (`() => {}`), template literals, destructuring.
- **Execution Model**: **Synchronous**. All methods must run synchronously. Do **not** use `async`, `await`, or native `Promise`. Network operations (`host.fetch()`, etc.) block until completion and return data directly.
- **Timeout**: Strict 30-second (`30,000ms`) execution limit per method invocation. Exceeding this limit aborts the script.
- **Sandbox Restrictions**: No access to Java classes/packages (`Packages`, `java.*`, `com.*`), browser globals (`window`, `document`, `fetch`, `localStorage`), or filesystem. Use `host.*` APIs exclusively.
- **Entry Point**: The script must declare a top-level `source` object.

```javascript
var source = {
  id: "example",
  displayName: "Example Source",
  origin: "https://example.com",
  apiLevel: 1,

  catalogs: [
    { id: "LATEST", label: "Latest", paginated: true }
  ],

  // Required methods
  loadListing: function(catalogId, page) { /* ... */ },
  loadEpisodes: function(item, page) { /* ... */ },
  resolveImages: function(episode, item) { /* ... */ }
};
```

---

## 2. `source` Configuration Properties

| Property | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `string` | **Yes** | - | Unique alphanumeric ID (lowercase + hyphens, e.g. `"mangadex"`). |
| `displayName` | `string` | **Yes** | - | Display title shown in UI. |
| `origin` | `string` | No | `""` | Base site URL (e.g. `"https://example.com"`). |
| `apiLevel` | `number` | No | `1` | API level (always `1`). |
| `catalogs` | `Catalog[]` | No | `[{id: "LATEST", label: "최신", paginated: true}]` | Browse tabs: `{ id: string, label: string, paginated: boolean }`. |
| `searchPlaceholder` | `string` | No | `"제목 검색"` | Search input placeholder text. |
| `userAgent` | `string` | No | Default UA | Custom HTTP `User-Agent`. |
| `referer` | `string` | No | `origin` | Default HTTP `Referer`. |
| `extraHeaders` | `Record<string, string>` | No | `{}` | Global HTTP headers attached to all requests. |
| `episodePageSize` | `number` | No | `100` | Episodes per page for pagination. |
| `notificationMode` | `"NONE"` \| `"LATEST_INTERSECTION"` \| `"PER_FAVORITE"` | No | `"NONE"` | Background update check mode. |
| `progressDisplay` | `"LAST_READ_ORDER"` \| `"READ_COUNT"` | No | `"LAST_READ_ORDER"` | Default reading progress format. |
| `defaultLanguage` | `string` | No | `null` | Default language code (e.g. `"ko"`, `"en"`). |
| `emptyListingOk` | `boolean` | No | `false` | If `true`, empty listing results do not throw an error. |
| `emptyEpisodesOk` | `boolean` | No | `false` | If `true`, empty episode lists do not throw an error. |

---

## 3. Data Models

### 3.1 `ToonItem` (Comic Series)
```typescript
interface ToonItem {
  id: string;                     // Required: Unique series identifier
  title: string;                  // Required: Series title
  thumbUrl: string;               // Required: Cover image URL (empty string allowed)
  href: string;                   // Required: URL to series page
  genre?: string;                 // Optional: Genres, tags, or description
  updatedAt?: string;             // Optional: Release/update date ("YYYY.MM.DD" or "YYYY-MM-DD")
  ranking?: string;               // Optional: Numeric rank or rating string
  isNew?: boolean;                // Optional: New badge flag
  entryEpisodeId?: string;        // Optional: Episode wrId for direct reader entry (one-shots)
  artistChoices?: {               // Optional: Artist or tag filters
    slug: string;
    displayName: string;
  }[];
}
```

### 3.2 `EpisodeItem` (Chapter)
```typescript
interface EpisodeItem {
  wrId: string;                   // Required: Unique episode identifier
  title: string;                  // Required: Episode title
  href: string;                   // Required: URL to episode reader page
  date?: string;                  // Optional: Release date ("YYYY-MM-DD")
  thumbUrl?: string;              // Optional: Episode thumbnail URL
  artistChoices?: {               // Optional: Filter tags
    slug: string;
    displayName: string;
  }[];
}
```

### 3.3 `PageInfo`
```typescript
interface PageInfo {
  currentPage: number;            // 1-indexed current page number
  lastPage: number;               // Last page number
}
```

---

## 4. Required Lifecycle Methods

### 4.1 `loadListing(catalogId, page)`
Fetches comic cards for a selected catalog tab and page.

* **Parameters**:
  - `catalogId` (`string`): ID from `source.catalogs`.
  - `page` (`number`): 1-indexed page number.
* **Returns**: `{ items: ToonItem[], pageInfo: PageInfo }` (or `{ items: ToonItem[], currentPage: number, lastPage: number }`)

```javascript
loadListing: function(catalogId, page) {
  const url = `${source.origin}/comics?cat=${catalogId}&page=${page}`;
  const html = host.fetchText({ url });
  const doc = host.parseHtml(html, url);

  const items = doc.select(".comic-card").map(el => ({
    id: host.match(el.absUrlOf("a", "href"), "/series/([^/]+)", 1),
    title: host.unescape(el.textOf(".title")),
    thumbUrl: el.imgUrlOf("img", url),
    href: el.absUrlOf("a", "href"),
    updatedAt: el.textOf(".date")
  }));

  return { items, pageInfo: host.parsePageInfo(doc) };
}
```

### 4.2 `loadEpisodes(item, page)`
Fetches the chapter list for a comic series.

* **Parameters**:
  - `item` (`ToonItem`): Parent series item.
  - `page` (`number`): 1-indexed page number.
* **Returns**: `{ items: EpisodeItem[], pageInfo: PageInfo }`

```javascript
loadEpisodes: function(item, page) {
  const html = host.fetchText({ url: `${item.href}?page=${page}` });
  const doc = host.parseHtml(html, item.href);

  const items = doc.select(".episode-item").map(el => ({
    wrId: host.match(el.absUrlOf("a", "href"), "/ep/(\\d+)", 1),
    title: el.textOf(".name"),
    href: el.absUrlOf("a", "href"),
    date: el.textOf(".date"),
    thumbUrl: el.absUrlOf("img", "src")
  }));

  return { items, pageInfo: host.parsePageInfo(doc) };
}
```

### 4.3 `resolveImages(episode, item)`
Resolves reader page image URLs in reading order.

* **Parameters**:
  - `episode` (`EpisodeItem`): Target episode.
  - `item` (`ToonItem`): Parent comic series.
* **Returns**: `string[]` - Ordered list of absolute image URLs.
* **Error**: Must throw an `Error` if no valid images are resolved.

```javascript
resolveImages: function(episode, item) {
  const html = host.fetchText({ url: episode.href });
  const pages = host.extractImages(html, episode.href);
  if (!pages || pages.length === 0) {
    throw new Error(`Failed to resolve images: ${episode.title}`);
  }
  return pages;
}
```

---

## 5. Optional Methods

### 5.1 Search & Discovery
```javascript
// Search comics by keyword or filters
// query: { text: string, language?: string, type?: string }
// Returns: ToonItem[]
search: function(query) {
  const q = (query && query.text || "").trim();
  if (!q) return [];
  const html = host.fetchText({ url: `${source.origin}/search?q=${encodeURIComponent(q)}` });
  const doc = host.parseHtml(html);
  return doc.select(".item").map(el => ({ /* ToonItem */ }));
},

// Autocomplete / tag suggestions
// query: { text: string }
// Returns: { ns: string, tag: string, count: number }[]
suggest: function(query) {
  return [
    { ns: "tag", tag: "action", count: 120 }
  ];
},

// Direct cover image URL resolution
// Returns: string | null
coverUrl: function(toonId) {
  return `https://cdn.example.com/covers/${toonId}.jpg`;
},

// Resolve parent series item when filtered by artist/tag
// Returns: ToonItem | null
resolveParent: function(item, choice, entryEpisodeId) {
  return {
    id: `artist:${choice.slug}`,
    title: choice.displayName,
    thumbUrl: item.thumbUrl,
    href: item.href,
    entryEpisodeId: entryEpisodeId || item.entryEpisodeId
  };
}
```

### 5.2 Network & Domain Control
```javascript
// Domain ownership verification for image fallback routing & Referer injection
// Optional: Defaults to `origin` and its subdomains (*.origin).
// Only required when images are served from external/third-party CDNs or mirror domains.
// Returns: boolean
ownsHost: function(host) {
  const h = String(host || "").toLowerCase();
  return h === "cdn.example.net" || h.endsWith(".external-cdn.org");
},

// Fallback alternative URLs when an image fails to load (403, 404, 502, timeout)
// Returns: string[]
imageFallbacks: function(url) {
  if (url && url.includes("/hi-res/")) {
    return [url.replace("/hi-res/", "/low-res/")];
  }
  return [];
},

// Custom Referer header for specific image requests
// Returns: string
imageReferer: function(url) {
  return "https://example.com/";
},

// Route via proxy (true) or direct connection (false)
// Returns: boolean (Default: true)
useProxy: function(url) {
  return true;
}
```

### 5.3 Notifications & Lifecycle
```javascript
// Determine if a specific series supports background chapter notifications
// Returns: boolean
supportsChapterNotifications: function(item) {
  return true;
},

// Filter/prioritize user's favorites for background update checking
// favorites: ToonItem[]
// Returns: ToonItem[]
notificationCandidates: function(favorites) {
  return favorites;
},

// Dynamic callback when user settings change (e.g. language)
// config: { language: string | null }
applyConfig: function(config) {
  // apply config updates
}
```

---

## 6. Global `host` API Reference

### 6.1 Network & HTTP

#### `host.fetch(spec)`
Performs a synchronous HTTP request with automatic retries for transient HTTP errors (403, 408, 429, 500, 502, 503, 504).
* **Argument**: `{ url: string, method?: "GET" | "HEAD", headers?: Record<string, string> }`
* **Returns**: `HostFetchResult`
  - `.code`: HTTP status code (`number`).
  - `.header(name: string)`: Header value (`string | null`).
  - `.text()`: UTF-8 decoded body (`string`).
  - `.totalLength()`: Content length (`number | null`).
  - `.body`: Raw bytes (`Bytes`).

#### `host.fetchText(spec)`
Performs a GET request and returns UTF-8 body string. Throws on non-2xx status.
* **Argument**: `{ url: string, headers?: Record<string, string> }`
* **Returns**: `string`

#### `host.fetchJson(spec)`
Performs a GET request and parses response as JSON. Throws on non-2xx status or invalid JSON.
* **Argument**: `{ url: string, headers?: Record<string, string> }`
* **Returns**: `any`

#### `host.fetchAll(specs, concurrency?)`
Fetches multiple requests concurrently.
* **Arguments**: `specs: HostFetchSpec[]`, `concurrency?: number` (Default: `6`)
* **Returns**: `HostFetchResult[]`

#### `host.isAccessible(url)`
Performs a fast HEAD request to check reachability (HTTP 200).
* **Returns**: `boolean`

---

### 6.2 HTML DOM Parsing (Jsoup)

#### `host.parseHtml(htmlText, baseUrl?)`
Parses HTML string into queryable `HostHtmlDoc`.
* **Returns**: `HostHtmlDoc`

#### DOM Query Methods (`HostHtmlDoc` & `HostHtmlEl`)
| Method | Target | Description |
| :--- | :--- | :--- |
| `.select(selector)` | Doc & El | Returns `HostHtmlEl[]` matching CSS selector. |
| `.selectFirst(selector)` | Doc & El | Returns first matching `HostHtmlEl` or `null`. |
| `.text()` | El | Combined inner text content. |
| `.html()` | El | Inner HTML string. |
| `.attr(name)` | El | Value of specified attribute. |
| `.absUrl(attr)` | El | Resolves relative attribute URL to absolute URL. |
| `.imgUrl(baseUrl?)` | El | Extracts image URL from `data-src`, `data-original`, `data-lazy`, `src`, or CSS background as absolute URL. |
| `.textOf(selector)` | Doc & El | Shorthand: `selectFirst(selector)?.text() \|\| ""` |
| `.attrOf(selector, attr)` | Doc & El | Shorthand: `selectFirst(selector)?.attr(attr) \|\| ""` |
| `.absUrlOf(selector, attr)` | Doc & El | Shorthand: `selectFirst(selector)?.absUrl(attr) \|\| ""` |
| `.imgUrlOf(selector, baseUrl?)` | Doc & El | Shorthand: `selectFirst(selector)?.imgUrl(baseUrl) \|\| ""` |
| `.bgUrl()` | El | Extracts URL from CSS `style="background-image: url(...)"`. |

---

### 6.3 Smart Extraction & Helpers

#### `host.extractImages(htmlOrText, baseUrl?)`
Automatically extracts viewer page images from HTML or script.
- Scans JS array variables: `img_list`, `img_list_2`, `data_list`, `arr_img`, `images`, `toon_images`.
- Scans viewer containers: `#scroll-list`, `.view-content`, `.scroll-viewer`, `#page-list`, `#contents-wrapper`, `#toon_view`, `.comic-view`.
- Handles `data-src`, `data-original`, `data-lazy`, `src`, inline CSS backgrounds, escaped slashes (`\/`), and Data URIs.
- Filters out UI noise (banners, logos, avatars, captchas).
- Probes image accessibility via `isAccessible()` if multiple candidates exist.
* **Returns**: `string[]`

#### `host.parsePageInfo(docOrHtml)`
Auto-parses standard pagination (`.pg_current`, `.pagination .active`, `page=N`).
* **Returns**: `{ currentPage: number, lastPage: number }`

---

### 6.4 Text, Parsing & Utilities

| Method | Signature | Description |
| :--- | :--- | :--- |
| `host.absUrl` | `(url: string, baseUrl?: string) => string` | Resolves relative URL to absolute URL. |
| `host.unescape` | `(text: string) => string` | Unescapes HTML entities (`&amp;` -> `&`, `&#039;` -> `'`, `&quot;` -> `"`, `&#8217;` -> `’`, etc.). |
| `host.match` | `(text: string, regex: string, groupIndex?: number) => string \| null` | Regex match extraction (default group: `1`). |
| `host.digits` | `(text: string) => string` | Strips all non-digit characters (`"Ep. 42"` -> `"42"`). |
| `host.slug` | `(text: string) => string` | Converts string to lowercase slug (`"A B"` -> `"a_b"`). |
| `host.json` | `(jsonString: string) => any` | Parses JSON string. |
| `host.utf8` | `(bytes: Bytes) => string` | Converts `Bytes` to UTF-8 string. |
| `host.jsonFromBody` | `(bytes: Bytes) => any` | Converts `Bytes` directly to parsed JSON. |
| `host.evalSiteJs` | `(kind: "galleryinfo" \| "gg", text: string) => any` | Evaluates site-specific JS in isolated sandbox. |
| `host.fetchInt32Index` | `(url: string, page?: number, pageSize?: number) => { ids: number[], currentPage: number, lastPage: number }` | Range-request binary 32-bit big-endian index reader. |
| `host.intersectIndexUrls`| `(urls: string[], pageSize?: number) => number[]` | Computes intersection across multiple binary index streams. |
| `host.cacheGet` | `(key: string) => any \| null` | Retrieves item from in-memory LRU cache. |
| `host.cachePut` | `(key: string, value: any, ttlMs?: number) => void` | Stores item in cache (default TTL: 10 minutes). |
| `host.log` | `(level: string, message: string) => void` | Prints debug log (`"INFO"`, `"WARN"`, `"ERROR"`). |
| `host.language` | `string \| null` *(Property)* | User's active language code in Comics8. |
| `host.apiLevel` | `number` *(Property)* | Host API level (currently `1`). |

---

## 7. Production Reference Implementation

Official reference source plugin (`peppercarrot.js`):

```javascript
// Pepper & Carrot (https://www.peppercarrot.com) - CC BY 4.0
const ORIGIN = "https://www.peppercarrot.com";

function getEpisodes(lang) {
  const code = (lang || "en").toLowerCase();
  const url = `${ORIGIN}/${code}/webcomics/peppercarrot.html`;
  const doc = host.parseHtml(host.fetchText({ url }), url);
  const figures = doc.select("figure.thumbnail");
  const episodes = [];

  for (let i = 0; i < figures.length; i++) {
    const fig = figures[i];
    const link = fig.selectFirst("a");
    const img = fig.selectFirst("img");
    const href = link ? link.absUrl("href") : "";
    const wrId = host.match(href, "/webcomic/([^/.]+)", 1);
    if (!wrId) continue;

    episodes.push({
      wrId: wrId,
      title: fig.textOf("figcaption a") || (img ? img.attr("alt") : wrId),
      thumbUrl: fig.imgUrlOf("img", ORIGIN),
      href: href,
      date: host.match(fig.textOf("figcaption"), "(\\d{4}-\\d{2}-\\d{2})", 1)
    });
  }
  return episodes;
}

var source = {
  id: "peppercarrot",
  displayName: "Pepper & Carrot",
  origin: ORIGIN,
  referer: ORIGIN + "/",
  catalogs: [
    { id: "ALL", label: "All (English)", paginated: false },
    { id: "KO", label: "한국어 (Korean)", paginated: false },
    { id: "FR", label: "Français", paginated: false },
    { id: "JA", label: "日本語", paginated: false }
  ],
  searchPlaceholder: "Search episodes (에피소드 검색)...",
  episodePageSize: 100,

  loadListing: function(catalogId, page) {
    const cat = String(catalogId || "ALL").toUpperCase();
    const lang = (cat === "KO") ? "ko" : (cat === "FR") ? "fr" : (cat === "JA") ? "ja" : "en";
    const episodes = getEpisodes(lang);
    const latest = episodes[0];

    const title = (lang === "ko") ? "페퍼와 캐롯 (Pepper & Carrot)" : "Pepper & Carrot";
    return {
      items: [{
        id: "peppercarrot-" + lang,
        title: title,
        thumbUrl: latest ? latest.thumbUrl : "",
        href: `${ORIGIN}/${lang}/webcomics/peppercarrot.html`,
        genre: "Webcomic, Fantasy (CC BY 4.0)",
        updatedAt: latest && latest.date ? latest.date.replace(/-/g, ".") : null
      }],
      pageInfo: { currentPage: 1, lastPage: 1 }
    };
  },

  loadEpisodes: function(item, page) {
    const lang = host.match(item && item.href, "peppercarrot.com/([^/]+)/", 1) || "en";
    return {
      items: getEpisodes(lang),
      pageInfo: { currentPage: 1, lastPage: 1 }
    };
  },

  search: function(query) {
    const q = (query && query.text || "").toLowerCase().trim();
    if (!q) return [];

    const episodes = getEpisodes("en");
    const results = [];
    for (let i = 0; i < episodes.length; i++) {
      const ep = episodes[i];
      if (ep.title.toLowerCase().includes(q) || ep.wrId.toLowerCase().includes(q)) {
        results.push({
          id: "peppercarrot-en",
          title: ep.title,
          thumbUrl: ep.thumbUrl,
          href: `${ORIGIN}/en/webcomics/peppercarrot.html`,
          updatedAt: ep.date ? ep.date.replace(/-/g, ".") : null,
          entryEpisodeId: ep.wrId
        });
      }
    }
    return results;
  },

  resolveImages: function(episode, item) {
    const doc = host.parseHtml(host.fetchText({ url: episode.href }), episode.href);
    const imgs = doc.select("img");
    const pages = [];
    const seen = {};

    for (let i = 0; i < imgs.length; i++) {
      const src = imgs[i].absUrl("src");
      if (src.includes("0_sources") && src.includes(".jpg")) {
        const hiRes = src.replace("/low-res/", "/hi-res/");
        if (!seen[hiRes]) {
          seen[hiRes] = true;
          pages.push(hiRes);
        }
      }
    }

    if (pages.length === 0) throw new Error("No images found: " + episode.title);
    return pages;
  },

  imageFallbacks: function(url) {
    if (url && url.includes("/hi-res/")) {
      return [url.replace("/hi-res/", "/low-res/")];
    }
    return [];
  },

  ownsHost: function(h) {
    return String(h || "").toLowerCase() === "davidrevoy.com";
  }
};
```

---

## 8. Authoring Rules & Constraints

1. **Do not use asynchronous keywords**: `async`, `await`, and `Promise` are forbidden. All operations must run synchronously.
2. **Do not use browser globals**: `window`, `document`, `fetch`, `XMLHttpRequest`, `localStorage` do not exist. Use `host.fetchText()`, `host.parseHtml()`, etc.
3. **Always use absolute URLs**: Convert relative links using `el.absUrl("href")`, `el.absUrl("src")`, or `host.absUrl(url, base)`.
4. **Throw on missing images**: In `resolveImages()`, throw an `Error` if the returned array is empty.
5. **Implement `ownsHost()` only for external CDNs**: `origin` and its subdomains (*.origin) are recognized automatically. Only implement `ownsHost()` if images or fallbacks use different domains.
6. **Normalize date formats**: Use `YYYY-MM-DD` or `YYYY.MM.DD` for `updatedAt` and `date`.
