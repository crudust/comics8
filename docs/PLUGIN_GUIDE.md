# Comics8 Source Plugin Development Guide

Comics8 uses JavaScript-based plugins to fetch comic/manga/webtoon listings, episode details, and reader page images.

This guide provides the complete specification for developing or generating Comics8 JavaScript source plugins, including all available runtime host APIs, lifecycle methods, failure fallback & retry mechanisms, and a production reference example.

---

## 1. Plugin Overview & Architecture

### 1.1 Runtime Environment
- **Engine**: Embedded Mozilla Rhino JavaScript runtime with isolated sandbox execution.
- **Compatibility**: Standard ECMAScript 5.1+ syntax. (Using `var` and ES5-compatible patterns is recommended for maximum reliability).
- **Host Bridge**: A global `host` object is automatically injected into the root scope providing HTTP, DOM parsing, caching, and regex utilities.
- **Entry Point**: The plugin script must declare a top-level `source` object.

```javascript
var source = {
  id: "peppercarrot",                         // Required: Unique alphanumeric identifier
  displayName: "Pepper & Carrot",             // Required: Display title in Comics8
  origin: "https://www.peppercarrot.com",     // Base site URL
  apiLevel: 1,                                // Host API level (default: 1)

  // Catalogs / Categories / Language tabs
  catalogs: [
    { id: "ALL", label: "All (English)", paginated: false },
    { id: "KO", label: "한국어 (Korean)", paginated: false }
  ],

  // 3 Core Required Methods
  loadListing: function(catalogId, page) { ... },
  loadEpisodes: function(item, page) { ... },
  resolveImages: function(episode, item) { ... },

  // Optional Methods (Search, Fallbacks, Domains, Suggestions)
  search: function(query) { ... },
  imageFallbacks: function(url) { ... },
  ownsHost: function(host) { ... },
  imageReferer: function(url) { ... },
  useProxy: function(url) { ... }
};
```

---

## 2. `source` Configuration Properties

| Property | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `string` | **Yes** | - | Unique source identifier (e.g., `"peppercarrot"`, `"mangadex"`). |
| `displayName` | `string` | **Yes** | - | User-facing source title shown in the app. |
| `origin` | `string` | No | `""` | Root site origin (e.g., `"https://example.com"`). |
| `apiLevel` | `number` | No | `1` | API level supported by the plugin. |
| `catalogs` | `Array<Catalog>` | No | `[{id: "LATEST", label: "최신", paginated: true}]` | Category/browse tabs. Format: `{ id: string, label: string, paginated: boolean }`. |
| `searchPlaceholder` | `string` | No | `"제목 검색"` | Search box placeholder text. |
| `referer` | `string` | No | `origin` | Default `Referer` header attached to HTTP requests. |
| `extraHeaders` | `object` | No | `{}` | Global HTTP headers attached to requests (e.g., `{ "Accept-Language": "ko-KR,ko;q=0.9" }`). |
| `episodePageSize` | `number` | No | `100` | Number of episodes per page for pagination. |
| `notificationMode` | `string` | No | `"NONE"` | Update check mode: `"NONE"`, `"LATEST_INTERSECTION"`, or `"PER_FAVORITE"`. |
| `progressDisplay` | `string` | No | `"LAST_READ_ORDER"` | Reading progress indicator: `"LAST_READ_ORDER"` (e.g. Ep 12) or `"READ_COUNT"` (e.g. 5/10). |
| `defaultLanguage` | `string` | No | `null` | Default language code (e.g., `"en"`, `"ko"`). |
| `emptyListingOk` | `boolean` | No | `false` | If `true`, empty listing results are treated as normal rather than errors. |
| `emptyEpisodesOk` | `boolean` | No | `false` | If `true`, empty episode lists are treated as normal rather than errors. |

---

## 3. Core API Methods Specification

### 3.1 `loadListing(catalogId, page)` [Required]
Fetches comic series cards for a given catalog tab and page.

* **Parameters**:
  - `catalogId` *(string)*: ID of the selected catalog (from `source.catalogs`).
  - `page` *(number)*: 1-indexed page number.
* **Return Value**:
  ```javascript
  {
    items: [
      {
        id: "series-slug",              // string (Required): Unique series ID
        title: "Comic Title",           // string (Required): Comic title
        thumbUrl: "https://.../thumb.jpg", // string (Required): Cover image URL
        href: "https://.../series/slug",// string (Required): URL to series details
        genre: "Fantasy, Comedy",       // string (Optional): Genre/tags/description
        updatedAt: "2026.08.31",        // string (Optional): Last updated date (YYYY.MM.DD)
        ranking: "1",                   // string (Optional): Rank number if applicable
        isNew: false,                   // boolean (Optional): New series badge
        entryEpisodeId: "ep-01",        // string (Optional): Direct entry episode ID
        artistChoices: [                // Array<{ slug, displayName }> (Optional): Artist filters
          { slug: "david_revoy", displayName: "David Revoy" }
        ]
      }
    ],
    pageInfo: {
      currentPage: 1,                  // number: Current page
      lastPage: 10                     // number: Last page number
    }
  }
  ```
  *(Note: You can also place `currentPage` and `lastPage` directly at the root of the returned object).*

---

### 3.2 `loadEpisodes(item, page)` [Required]
Fetches the episode/chapter list for a specific comic series.

* **Parameters**:
  - `item` *(object: ToonItem)*: Series item object returned by `loadListing` or `search` (`item.id`, `item.href`, `item.title`, etc.).
  - `page` *(number)*: 1-indexed episode page number.
* **Return Value**:
  ```javascript
  {
    items: [
      {
        wrId: "ep-01",                 // string (Required): Unique episode/chapter ID
        title: "Episode 1: Potions",   // string (Required): Episode title
        href: "https://.../ep1.html",  // string (Required): URL to episode reader
        date: "2026-08-31",            // string (Optional): Release date
        thumbUrl: "https://.../ep1.jpg", // string (Optional): Episode thumbnail
        artistChoices: []              // Array (Optional)
      }
    ],
    pageInfo: {
      currentPage: 1,
      lastPage: 1
    }
  }
  ```

---

### 3.3 `resolveImages(episode, item)` [Required]
Extracts all comic page image URLs for reading an episode.

* **Parameters**:
  - `episode` *(object: EpisodeItem)*: Episode item (`episode.wrId`, `episode.href`, `episode.title`, etc.).
  - `item` *(object: ToonItem)*: The parent comic series item.
* **Return Value**:
  - `Array<string>`: List of absolute image URLs in reading order:
  ```javascript
  [
    "https://example.com/comics/ep1_p01.jpg",
    "https://example.com/comics/ep1_p02.jpg",
    "https://example.com/comics/ep1_p03.jpg"
  ]
  ```
  *Throw an `Error` if no valid images could be resolved.*

---

### 3.4 `search(query)` [Optional]
Searches comic series by keyword or filters.

* **Parameters**:
  - `query` *(object: SearchQuery)*:
    - `query.text` *(string)*: User search keyword.
    - `query.language` *(string, optional)*: Language filter.
    - `query.type` *(string, optional)*: Type/category filter.
* **Return Value**:
  - `Array<ToonItem>`: List of matching series items (same structure as `loadListing` items).
  ```javascript
  search: function(query) {
    var q = (query && query.text || "").trim();
    if (!q) return [];
    // ... fetch and parse search results ...
    return results;
  }
  ```

---

### 3.5 `suggest(query)` [Optional]
Provides search auto-complete / tag suggestions.

* **Parameters**:
  - `query` *(object: SearchQuery)*: Contains `query.text`.
* **Return Value**:
  - `Array<{ ns: string, tag: string, count: number }>`

---

## 4. Fallback, Retry & Domain Control Methods

Comics8 provides first-class mechanisms for image failovers, CDN rotating, and mirror domain fallbacks.

### 4.1 `imageFallbacks(url)` [Recommended for multi-CDN / mirrors]
Invoked when loading an image URL fails (HTTP 403, 404, 502, network timeout, or corrupted data). The app will automatically attempt downloading from the returned fallback URLs in sequential order.

* **Parameters**: `url` *(string)* - The failed image URL.
* **Return Value**: `Array<string>` - List of alternative URLs to try.

```javascript
imageFallbacks: function(url) {
  if (!url) return [];
  // Example 1: Fallback from high-resolution to low-resolution
  if (url.indexOf("/hi-res/") !== -1) {
    return [url.replace("/hi-res/", "/low-res/")];
  }
  // Example 2: Fallback across backup CDN mirrors
  if (url.indexOf("cdn1.example.com") !== -1) {
    return [
      url.replace("cdn1.example.com", "cdn2.example.com"),
      url.replace("cdn1.example.com", "cdn3.example.com")
    ];
  }
  return [];
}
```

### 4.2 `ownsHost(host)` [Required when using `imageFallbacks` or custom domains]
Determines if a given host/domain is managed by this source plugin.
* **Why it matters**: When an image fails to load, Comics8 uses `ownsHost()` to locate the responsible source plugin and invoke its `imageFallbacks()` and `imageReferer()` handlers.
* **Parameters**: `host` *(string)* - Lowercase host name (e.g. `"peppercarrot.com"`).
* **Return Value**: `boolean`

```javascript
ownsHost: function(h) {
  var hostName = String(h || "").toLowerCase();
  return hostName === "peppercarrot.com" ||
         hostName === "www.peppercarrot.com" ||
         hostName.endsWith(".peppercarrot.com");
}
```

### 4.3 `imageReferer(url)` [Optional]
Customizes the HTTP `Referer` header for specific image requests (to bypass anti-hotlinking protections).

* **Parameters**: `url` *(string)* - Target image URL.
* **Return Value**: `string` - Referer URL string.

```javascript
imageReferer: function(url) {
  return "https://www.peppercarrot.com/";
}
```

### 4.4 `useProxy(url)` [Optional]
Controls whether requests to a specific URL should route through the Comics8 subscription proxy or connect directly.

* **Parameters**: `url` *(string)* - Target URL.
* **Return Value**: `boolean` (Default: `true`). Return `false` to enforce direct connection.

---

## 5. Global `host` Utility Reference

The global `host` object is pre-injected into the JavaScript environment and provides powerful built-in utilities:

### 5.1 Network & HTTP

#### `host.fetch(spec)`
Performs an HTTP request.
* **Argument**: `{ url: string, method?: "GET"|"HEAD", headers?: object }`
* **Returns**: `HostFetchResult`
  - `.code`: HTTP status code (`number`, e.g. `200`).
  - `.header(name)`: Get response header value (`string | null`).
  - `.text()`: Get response body as UTF-8 string (`string`).
  - `.totalLength()`: Content length (`number | null`).
  - `.body`: Raw response bytes (`Bytes`).

#### `host.fetchText(spec)`
Convenience method that executes a GET request and returns the response body as a string. Throws an error if the HTTP status code is not 2xx.
```javascript
var html = host.fetchText({ url: "https://example.com/comic/1" });
```

#### `host.fetchJson(spec)`
Fetches a remote URL and parses the response body as JSON.
```javascript
var data = host.fetchJson({
  url: "https://api.example.com/v1/comics",
  headers: { "Authorization": "Bearer token" }
});
```

#### `host.fetchAll(specs, concurrency)`
Performs multiple HTTP requests concurrently.
* **Arguments**: `specs` *(Array<FetchSpec>)*, `concurrency` *(number, default: 6)*.
* **Returns**: `Array<HostFetchResult>`.

#### `host.isAccessible(url)`
Sends a fast HEAD request to check if a URL is reachable and returns HTTP 200 OK.
* **Returns**: `boolean`.

---

### 5.2 HTML DOM Parsing & Traversal

#### `host.parseHtml(htmlText, baseUrl?)`
Parses an HTML string into a queryable `HostHtmlDoc` document backed by Jsoup.

```javascript
var doc = host.parseHtml(htmlString, "https://example.com");
```

#### Document & Element Methods (`HostHtmlDoc`, `HostHtmlEl`)
| Method | Description |
| :--- | :--- |
| `.select(cssSelector)` | Returns an `Array<HostHtmlEl>` of all matching elements. |
| `.selectFirst(cssSelector)` | Returns the first matching `HostHtmlEl` or `null`. |
| `.text()` *(Element only)* | Returns the text content of the element. |
| `.html()` *(Element only)* | Returns the inner HTML of the element. |
| `.attr(attributeName)` *(Element only)* | Returns the value of the specified attribute. |
| `.absUrl(attributeName)` *(Element only)* | Resolves relative URLs into absolute URLs (e.g. `el.absUrl("href")`, `img.absUrl("src")`). |
| `.textOf(cssSelector)` | Shorthand: Finds the first matching child and returns its `.text()` (or `""`). |
| `.attrOf(cssSelector, attr)` | Shorthand: Finds the first matching child and returns its `.attr(attr)` (or `""`). |
| `.absUrlOf(cssSelector, attr)` | Shorthand: Finds the first matching child and returns its `.absUrl(attr)` (or `""`). |
| `.bgUrl()` *(Element only)* | Extracts the image URL from inline CSS `style="background-image: url(...)"`. |

---

### 5.3 Parsing, Text & Caching Helpers

| Method | Signature | Description |
| :--- | :--- | :--- |
| `host.absUrl` | `(url: string, baseUrl?: string) => string` | Resolves relative URL into an absolute URL. |
| `host.match` | `(text: string, regex: string, groupIndex?: number) => string \| null` | Extracts regex capture group (default group: 1). |
| `host.digits` | `(text: string) => string` | Strips all non-digit characters (e.g. `"Ep. 42 (2026)"` -> `"422026"`). |
| `host.slug` | `(text: string) => string` | Converts string into a clean lowercase slug (spaces to `_`). |
| `host.extractImages` | `(htmlOrText: string, baseUrl?: string) => Array<string>` | Automatically detects & extracts comic images from standard viewer DOMs or JS arrays (`var img_list = [...]`). |
| `host.parsePageInfo` | `(docOrHtml: HostHtmlDoc \| string) => { currentPage: number, lastPage: number }` | Automatically parses standard pagination elements (`.pagination`, `page=N`). |
| `host.json` | `(jsonString: string) => any` | Parses a JSON string. |
| `host.cacheGet` | `(key: string) => any \| null` | Retrieves an item from the in-memory LRU cache. |
| `host.cachePut` | `(key: string, value: any, ttlMs?: number) => void` | Stores an item in the in-memory cache (default TTL: 10 minutes). |
| `host.log` | `(level: string, message: string) => void` | Emits a debug log (e.g., `host.log("INFO", "Loaded " + items.length)`). |
| `host.language` | `string \| null` *(Property)* | User's currently active language in Comics8. |
| `host.apiLevel` | `number` *(Property)* | App's host API level. |

---

## 6. Complete Reference Implementation: Pepper & Carrot

The following is a production-ready reference plugin implementing all core methods, multi-language catalogs, search, image resolution, and fallback failovers:

```javascript
// Pepper & Carrot (https://www.peppercarrot.com)
// Open-source webcomic by David Revoy, licensed under CC BY 4.0.
// Official reference source plugin for Comics8.

var ORIGIN = "https://www.peppercarrot.com";

// Helper: fetch and parse episode list from the webcomics page
function getEpisodes(lang) {
  var code = (lang || "en").toLowerCase();
  var url = ORIGIN + "/" + code + "/webcomics/peppercarrot.html";
  var doc = host.parseHtml(host.fetchText({ url: url }), url);
  var figures = doc.select("figure.thumbnail");
  var episodes = [];

  for (var i = 0; i < figures.length; i++) {
    var fig = figures[i];
    var link = fig.selectFirst("a");
    var img = fig.selectFirst("img");
    var href = link ? link.absUrl("href") : "";
    var wrId = host.match(href, "/webcomic/([^/.]+)", 1);
    if (!wrId) continue;

    episodes.push({
      wrId: wrId,
      title: fig.textOf("figcaption a") || (img ? img.attr("alt") : wrId),
      thumbUrl: img ? img.absUrl("src") : "",
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

  // 1. Catalog Listing: Return "Pepper & Carrot" series card for the selected language
  loadListing: function (catalogId, page) {
    var cat = String(catalogId || "ALL").toUpperCase();
    var lang = (cat === "KO") ? "ko" : (cat === "FR") ? "fr" : (cat === "JA") ? "ja" : "en";
    var episodes = getEpisodes(lang);
    var latest = episodes[0];

    var title = (lang === "ko") ? "페퍼와 캐롯 (Pepper & Carrot)" : "Pepper & Carrot";
    return {
      items: [{
        id: "peppercarrot-" + lang,
        title: title,
        thumbUrl: latest ? latest.thumbUrl : "",
        href: ORIGIN + "/" + lang + "/webcomics/peppercarrot.html",
        genre: "Webcomic, Fantasy (CC BY 4.0)",
        updatedAt: latest && latest.date ? latest.date.replace(/-/g, ".") : null
      }],
      pageInfo: { currentPage: 1, lastPage: 1 }
    };
  },

  // 2. Episode List: Return all episodes
  loadEpisodes: function (item, page) {
    var lang = host.match(item && item.href, "peppercarrot.com/([^/]+)/", 1) || "en";
    return {
      items: getEpisodes(lang),
      pageInfo: { currentPage: 1, lastPage: 1 }
    };
  },

  // 3. Search: Search episodes by title or episode number
  search: function (query) {
    var q = (query && query.text || "").toLowerCase().trim();
    if (!q) return [];

    var episodes = getEpisodes("en");
    var results = [];
    for (var i = 0; i < episodes.length; i++) {
      var ep = episodes[i];
      if (ep.title.toLowerCase().indexOf(q) !== -1 || ep.wrId.toLowerCase().indexOf(q) !== -1) {
        results.push({
          id: "peppercarrot-en",
          title: ep.title,
          thumbUrl: ep.thumbUrl,
          href: ORIGIN + "/en/webcomics/peppercarrot.html",
          updatedAt: ep.date ? ep.date.replace(/-/g, ".") : null,
          entryEpisodeId: ep.wrId
        });
      }
    }
    return results;
  },

  // 4. Resolve Images: Extract high-resolution comic pages
  resolveImages: function (episode, item) {
    var doc = host.parseHtml(host.fetchText({ url: episode.href }), episode.href);
    var imgs = doc.select("img");
    var pages = [];
    var seen = {};

    for (var i = 0; i < imgs.length; i++) {
      var src = imgs[i].absUrl("src");
      if (src.indexOf("0_sources") !== -1 && src.indexOf(".jpg") !== -1) {
        var hiRes = src.replace("/low-res/", "/hi-res/");
        if (!seen[hiRes]) {
          seen[hiRes] = true;
          pages.push(hiRes);
        }
      }
    }

    if (pages.length === 0) throw new Error("이미지를 찾을 수 없습니다: " + episode.title);
    return pages;
  },

  // 5. Image Fallbacks: If hi-res image fails (404/timeout), retry with low-res image
  imageFallbacks: function (url) {
    if (url && url.indexOf("/hi-res/") !== -1) {
      return [url.replace("/hi-res/", "/low-res/")];
    }
    return [];
  },

  // 6. Domain ownership: Identify domains managed by this source for fallback routing
  ownsHost: function (h) {
    var hostName = String(h || "").toLowerCase();
    return hostName === "peppercarrot.com" ||
           hostName === "www.peppercarrot.com" ||
           hostName === "davidrevoy.com";
  }
};
