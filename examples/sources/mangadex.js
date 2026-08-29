// MangaDex (https://mangadex.org)
// High-quality MangaDex source plugin for Comics8.
// Supports grid catalog listing (Latest, Popular, Top Rated, Multi-language),
// title & author search, chapter feeds, and image resolution via MangaDex@Home.

var ORIGIN = "https://mangadex.org";
var API_ORIGIN = "https://api.mangadex.org";
var UPLOADS_ORIGIN = "https://uploads.mangadex.org";

function pickLocalized(map, preferredLang) {
  if (!map) return "";
  var lang = (preferredLang || host.language || "en").toLowerCase();
  if (map[lang]) return String(map[lang]);
  if (map["en"]) return String(map["en"]);
  if (map["ja-ro"]) return String(map["ja-ro"]);
  if (map["ko-ro"]) return String(map["ko-ro"]);
  if (map["zh-ro"]) return String(map["zh-ro"]);
  for (var key in map) {
    if (map.hasOwnProperty(key) && map[key]) {
      return String(map[key]);
    }
  }
  return "";
}

function pickTitle(titleMap, altTitles, preferredLang) {
  var lang = (preferredLang || host.language || "en").toLowerCase();

  // 1. Title in preferred language
  if (titleMap && titleMap[lang]) return String(titleMap[lang]);

  // 2. Alt title in preferred language
  if (altTitles && altTitles.length) {
    for (var i = 0; i < altTitles.length; i++) {
      var a1 = altTitles[i];
      if (a1 && a1[lang]) return String(a1[lang]);
    }
  }

  // 3. Title in English
  if (titleMap && titleMap["en"]) return String(titleMap["en"]);

  // 4. Alt title in English
  if (altTitles && altTitles.length) {
    for (var j = 0; j < altTitles.length; j++) {
      var a2 = altTitles[j];
      if (a2 && a2["en"]) return String(a2["en"]);
    }
  }

  // 5. Title in Romanized Japanese/Korean
  if (titleMap && titleMap["ja-ro"]) return String(titleMap["ja-ro"]);
  if (titleMap && titleMap["ko-ro"]) return String(titleMap["ko-ro"]);

  // 6. Any other title
  if (titleMap) {
    for (var k in titleMap) {
      if (titleMap.hasOwnProperty(k) && titleMap[k]) return String(titleMap[k]);
    }
  }

  // 7. Any other alt title
  if (altTitles && altTitles.length) {
    for (var m = 0; m < altTitles.length; m++) {
      var a3 = altTitles[m];
      for (var k2 in a3) {
        if (a3.hasOwnProperty(k2) && a3[k2]) return String(a3[k2]);
      }
    }
  }

  return "";
}

function parseMangaCard(dataItem, preferredLang) {
  if (!dataItem || !dataItem.id) return null;
  var id = String(dataItem.id);
  var attrs = dataItem.attributes || {};
  var rels = dataItem.relationships || [];

  // Title
  var title = pickTitle(attrs.title, attrs.altTitles, preferredLang) || id;

  // Cover image
  var coverFileName = "";
  var authors = [];
  var seenArtists = {};

  for (var r = 0; r < rels.length; r++) {
    var rel = rels[r];
    if (!rel) continue;
    if (rel.type === "cover_art" && rel.attributes && rel.attributes.fileName) {
      coverFileName = rel.attributes.fileName;
    } else if ((rel.type === "author" || rel.type === "artist") && rel.attributes && rel.attributes.name) {
      var aName = String(rel.attributes.name).trim();
      var aId = String(rel.id || aName);
      if (aName && !seenArtists[aId]) {
        seenArtists[aId] = true;
        authors.push({
          slug: aId,
          displayName: aName
        });
      }
    }
  }

  var thumbUrl = coverFileName
    ? (UPLOADS_ORIGIN + "/covers/" + id + "/" + coverFileName + ".512.jpg")
    : "";

  // Genre / Tags / Status
  var tags = [];
  if (attrs.tags && attrs.tags.length) {
    for (var t = 0; t < attrs.tags.length; t++) {
      var tagObj = attrs.tags[t];
      var tagName = tagObj && tagObj.attributes && tagObj.attributes.name ? pickLocalized(tagObj.attributes.name, "en") : "";
      if (tagName) tags.push(tagName);
    }
  }
  var tagText = tags.slice(0, 3).join(", ");
  var statusText = attrs.status ? (attrs.status.charAt(0).toUpperCase() + attrs.status.slice(1)) : "";
  var genre = tagText + (tagText && statusText ? " • " : "") + statusText;

  // Updated at (latest chapter upload or manga updated date)
  var rawDate = attrs.latestUploadedChapter || attrs.updatedAt;
  var updatedAt = null;
  if (rawDate) {
    var dateMatch = host.match(String(rawDate), "(\\d{4}-\\d{2}-\\d{2})", 1);
    if (dateMatch) {
      updatedAt = dateMatch.replace(/-/g, ".");
    }
  }

  return {
    id: id,
    title: title,
    thumbUrl: thumbUrl,
    href: ORIGIN + "/title/" + id,
    genre: genre,
    updatedAt: updatedAt,
    artistChoices: authors
  };
}

var source = {
  id: "mangadex",
  displayName: "MangaDex",
  origin: ORIGIN,
  defaultLanguage: "en",
  searchPlaceholder: "Search titles, authors on MangaDex...",
  episodePageSize: 500,
  notificationMode: "PER_FAVORITE",
  extraHeaders: {
    "Referer": ORIGIN + "/"
  },

  catalogs: [
    { id: "LATEST", label: "Latest Updates", paginated: true },
    { id: "LATEST_EN", label: "Latest (English)", paginated: true },
    { id: "LATEST_KO", label: "Latest (한국어)", paginated: true },
    { id: "LATEST_JA", label: "Latest (日本語)", paginated: true },
    { id: "POPULAR", label: "Popular", paginated: true },
    { id: "TOP_RATED", label: "Top Rated", paginated: true },
    { id: "RECENT", label: "Recently Added", paginated: true },
    { id: "ALL", label: "All Languages", paginated: true }
  ],

  loadListing: function (catalogId, page) {
    var cat = String(catalogId || "LATEST").toUpperCase();
    var p = page < 1 ? 1 : page;
    var limit = 32;
    var offset = (p - 1) * limit;

    // Determine language filter
    var lang = null;
    if (cat === "LATEST") {
      lang = host.language || "en";
    } else if (cat === "LATEST_EN") {
      lang = "en";
    } else if (cat === "LATEST_KO") {
      lang = "ko";
    } else if (cat === "LATEST_JA") {
      lang = "ja";
    } else if (cat === "ALL") {
      lang = null;
    } else {
      lang = host.language || "en";
    }

    // Determine order
    var orderParam = "order[latestUploadedChapter]=desc";
    if (cat === "POPULAR") {
      orderParam = "order[followedCount]=desc";
    } else if (cat === "TOP_RATED") {
      orderParam = "order[rating]=desc";
    } else if (cat === "RECENT") {
      orderParam = "order[createdAt]=desc";
    }

    var queryParts = [
      "limit=" + limit,
      "offset=" + offset,
      orderParam,
      "includes[]=cover_art",
      "includes[]=author",
      "includes[]=artist",
      "contentRating[]=safe",
      "contentRating[]=suggestive",
      "contentRating[]=erotica"
    ];

    if (lang) {
      queryParts.push("availableTranslatedLanguage[]=" + encodeURIComponent(lang));
    }

    var apiUrl = API_ORIGIN + "/manga?" + queryParts.join("&");
    var res = host.fetchJson({
      url: apiUrl,
      headers: { "Referer": ORIGIN + "/" }
    });

    if (!res || res.result !== "ok" || !res.data) {
      return {
        items: [],
        pageInfo: { currentPage: p, lastPage: p }
      };
    }

    var items = [];
    for (var i = 0; i < res.data.length; i++) {
      var card = parseMangaCard(res.data[i], lang);
      if (card) items.push(card);
    }

    var total = parseInt(res.total, 10) || 0;
    var maxOffset = 10000;
    var totalCapped = Math.min(total, maxOffset);
    var lastPage = Math.max(1, Math.ceil(totalCapped / limit));

    return {
      items: items,
      pageInfo: {
        currentPage: p,
        lastPage: lastPage
      }
    };
  },

  search: function (query) {
    var q = (query && query.text || "").trim();
    if (!q) return [];

    var limit = 32;
    var lang = (query && query.language) || host.language || "en";

    var queryParts = [
      "title=" + encodeURIComponent(q),
      "limit=" + limit,
      "order[relevance]=desc",
      "includes[]=cover_art",
      "includes[]=author",
      "includes[]=artist",
      "contentRating[]=safe",
      "contentRating[]=suggestive",
      "contentRating[]=erotica"
    ];

    var apiUrl = API_ORIGIN + "/manga?" + queryParts.join("&");
    var res = host.fetchJson({
      url: apiUrl,
      headers: { "Referer": ORIGIN + "/" }
    });

    if (!res || res.result !== "ok" || !res.data) {
      return [];
    }

    var items = [];
    for (var i = 0; i < res.data.length; i++) {
      var card = parseMangaCard(res.data[i], lang);
      if (card) items.push(card);
    }

    return items;
  },

  loadEpisodes: function (item, page) {
    var mangaId = item ? (item.id || host.match(item.href, "/title/([a-f0-9-]+)", 1)) : "";
    if (!mangaId) {
      return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } };
    }

    var p = page < 1 ? 1 : page;
    var limit = 500;
    var offset = (p - 1) * limit;
    var lang = host.language || "en";

    function fetchFeed(withLang) {
      var parts = [
        "limit=" + limit,
        "offset=" + offset,
        "order[volume]=desc",
        "order[chapter]=desc",
        "order[publishAt]=desc",
        "includes[]=scanlation_group",
        "includes[]=user",
        "contentRating[]=safe",
        "contentRating[]=suggestive",
        "contentRating[]=erotica",
        "contentRating[]=pornographic"
      ];
      if (withLang) {
        parts.push("translatedLanguage[]=" + encodeURIComponent(withLang));
      }
      var url = API_ORIGIN + "/manga/" + mangaId + "/feed?" + parts.join("&");
      return host.fetchJson({
        url: url,
        headers: { "Referer": ORIGIN + "/" }
      });
    }

    var res = fetchFeed(lang);
    // If no chapters in preferred language, fallback to all languages
    if ((!res || !res.data || res.data.length === 0) && lang) {
      res = fetchFeed(null);
    }

    if (!res || res.result !== "ok" || !res.data) {
      return {
        items: [],
        pageInfo: { currentPage: p, lastPage: p }
      };
    }

    var episodes = [];
    for (var i = 0; i < res.data.length; i++) {
      var chObj = res.data[i];
      if (!chObj || !chObj.id) continue;
      var cAttrs = chObj.attributes || {};
      var cRels = chObj.relationships || [];

      var vol = cAttrs.volume ? ("Vol. " + cAttrs.volume + " ") : "";
      var ch = cAttrs.chapter ? ("Ch. " + cAttrs.chapter) : "";
      if (!vol && !ch) {
        ch = "Oneshot";
      }
      var chTitle = cAttrs.title ? (" - " + cAttrs.title) : "";

      var groupName = "";
      for (var g = 0; g < cRels.length; g++) {
        var grel = cRels[g];
        if (grel && grel.type === "scanlation_group" && grel.attributes && grel.attributes.name) {
          groupName = " (" + grel.attributes.name + ")";
          break;
        }
      }

      var langBadge = "";
      if (cAttrs.translatedLanguage && cAttrs.translatedLanguage.toLowerCase() !== lang) {
        langBadge = "[" + cAttrs.translatedLanguage.toUpperCase() + "] ";
      }

      var title = (langBadge + vol + ch + chTitle + groupName).trim();
      var dateStr = cAttrs.publishAt ? host.match(String(cAttrs.publishAt), "(\\d{4}-\\d{2}-\\d{2})", 1) : null;

      episodes.push({
        wrId: String(chObj.id),
        title: title,
        date: dateStr,
        thumbUrl: item ? item.thumbUrl : null,
        href: ORIGIN + "/chapter/" + chObj.id
      });
    }

    var total = parseInt(res.total, 10) || 0;
    var lastPage = Math.max(1, Math.ceil(total / limit));

    return {
      items: episodes,
      pageInfo: {
        currentPage: p,
        lastPage: lastPage
      }
    };
  },

  resolveImages: function (episode, item) {
    var chapterId = episode ? (episode.wrId || host.match(episode.href, "/chapter/([a-f0-9-]+)", 1)) : "";
    if (!chapterId) {
      throw new Error("챕터 ID를 찾을 수 없습니다.");
    }

    var apiUrl = API_ORIGIN + "/at-home/server/" + chapterId;
    var res = host.fetchJson({
      url: apiUrl,
      headers: { "Referer": ORIGIN + "/" }
    });

    if (!res || res.result !== "ok" || !res.baseUrl || !res.chapter || !res.chapter.hash) {
      throw new Error("MangaDex 이미지 서버 응답 오류");
    }

    var baseUrl = res.baseUrl;
    var hash = res.chapter.hash;
    var files = res.chapter.data || [];
    if (files.length === 0) {
      files = res.chapter.dataSaver || [];
    }

    if (files.length === 0) {
      throw new Error("만화 이미지를 찾을 수 없습니다.");
    }

    var images = [];
    for (var i = 0; i < files.length; i++) {
      images.push(baseUrl + "/data/" + hash + "/" + files[i]);
    }

    return images;
  },

  imageFallbacks: function (url) {
    if (!url) return [];
    var match = url.match(/\/data\/([a-f0-9]+)\/(.+)$/i);
    if (match) {
      var hash = match[1];
      var file = match[2];
      return [
        UPLOADS_ORIGIN + "/data/" + hash + "/" + file
      ];
    }
    return [];
  },

  ownsHost: function (h) {
    var hostName = String(h || "").toLowerCase();
    return hostName === "mangadex.org" ||
           hostName.endsWith(".mangadex.org") ||
           hostName === "mangadex.network" ||
           hostName.endsWith(".mangadex.network");
  },

  imageReferer: function (url) {
    return ORIGIN + "/";
  }
};
