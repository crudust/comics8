# Comics8 Source Plugin Development Guide

Comics8 uses JavaScript-based plugins to fetch comic/manga/webtoon listings, episode details, and page images.

---

## 1. Plugin Overview & Lifecycle

Each plugin is a standalone `.js` file that registers a source definition via `source`:

```javascript
source = {
  id: "my-source-id",              // Unique alphanumeric ID (e.g., 'peppercarrot')
  name: "My Source Name",          // Display title in Comics8
  baseUrl: "https://example.com",  // Root site URL
  episodePageSize: 100,            // Page size for pagination
  catalogs: [                      // Category / language / browse tabs
    { id: "LATEST", name: "최신순", href: "https://example.com/latest" },
    { id: "POPULAR", name: "인기순", href: "https://example.com/popular" }
  ],

  // 4 Core Required Methods
  loadListing: function(catalogId, page) { ... },
  loadEpisodes: function(seriesHref, page) { ... },
  resolveImages: function(episodeHref) { ... },
  search: function(query) { ... }
};
```

---

## 2. API Methods Specification

### 2.1 `loadListing(catalogId, page)`
Fetches the list of comic series for a given catalog tab and page number.

* **Arguments**:
  - `catalogId` *(string)*: ID of the selected catalog (from `source.catalogs`).
  - `page` *(number)*: 1-indexed page number.
* **Return Value**:
  ```javascript
  {
    items: [
      {
        id: "series-slug",
        title: "Comic Title",
        thumbUrl: "https://example.com/thumb.jpg",
        href: "https://example.com/series/slug",
        author: "Author Name" // Optional
      }
    ],
    currentPage: 1,
    lastPage: 10
  }
  ```

---

### 2.2 `loadEpisodes(seriesHref, page)`
Fetches episodes for a specific series.

* **Arguments**:
  - `seriesHref` *(string)*: URL of the series page.
  - `page` *(number)*: Episode list page number (1-indexed).
* **Return Value**:
  ```javascript
  {
    items: [
      {
        wrId: "ep-01",
        title: "Episode 1: The Beginning",
        href: "https://example.com/series/slug/ep1",
        date: "2026-08-25" // Optional
      }
    ],
    currentPage: 1,
    lastPage: 1
  }
  ```

---

### 2.3 `resolveImages(episodeHref)`
Extracts all image URLs for reading an episode.

* **Arguments**:
  - `episodeHref` *(string)*: URL of the episode reader page.
* **Return Value**:
  - `Array<string>`: List of absolute image URLs in reading order:
  ```javascript
  [
    "https://example.com/comics/ep1_p01.jpg",
    "https://example.com/comics/ep1_p02.jpg",
    "https://example.com/comics/ep1_p03.jpg"
  ]
  ```

---

### 2.4 `search(query)`
Searches series by keyword.

* **Arguments**:
  - `query` *(string)*: Search term entered by user.
* **Return Value**:
  - `Array<Item>`: List of matching series items:
  ```javascript
  [
    {
      id: "search-result-1",
      title: "Matching Comic",
      thumbUrl: "https://example.com/thumb.jpg",
      href: "https://example.com/series/result1"
    }
  ]
  ```

---

## 3. Global Helpers & Utilities

Comics8 provides built-in helper utilities inside the JavaScript runtime environment:

* **`http.get(url, headers)`**: Performs an HTTP GET request and returns `{ status: 200, body: "..." }`.
* **`http.post(url, body, headers)`**: Performs an HTTP POST request.
* **`html.parse(htmlString)`**: Returns a DOM query wrapper supporting CSS selectors (`.select()`, `.attr()`, `.text()`).

---

## 4. Reference Sample Implementation

See [`examples/sources/peppercarrot.js`](../examples/sources/peppercarrot.js) for a complete, production-ready reference plugin implementing all 4 methods with multi-language catalogs and hi-res image fallback resolution.
