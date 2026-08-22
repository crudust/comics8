// Sample 11toon parser for in-app import. Not bundled in the APK or server.
var ORIGIN = "http://103.204.13.68:8904";
var COVER_HOST = "https://11toon8.com";
var ORIGIN_HOST = "103.204.13.68";
var ELEVEN_HOST = /^(www\.)?11toon\d*\.com$/;
var PL_HOST = /^(www\.)?pl\d{4}\.com$/;
var KNOWN_DOMAINS = [
  "pl4050.com",
  "pl3040.com",
  "pl3030.com",
  "pl4040.com",
  "pl1020.com",
  "pl2030.com",
  "pl5060.com"
];
var DATE_RE = /(\d{2}\.\d{2})(?:\((\d+)\))?/;
var FULL_DATE_RE = /(\d{2}\.\d{2}\.\d{2})/;
var BG_URL_RE = /url\(\s*(['"]?)(.+?)\1\s*\)/i;
var WR_ID_RE = /wr_id=(\d+)/;
var PAGE_RE = /(?:\?|&|&amp;)page=(\d+)/;
var JS_IMG_ARRAY_RE = /(?:var|let|const)\s+(?:img_list|img_list_2|data_list|arr_img|images)\s*=\s*\[([\s\S]*?)\]/gi;
var JS_STRING_URL_RE = /["']([^"']+\.(?:jpg|jpeg|png|webp|gif)(?:\?[^"']*)?)["']/gi;
var HREF_RE = /location\.href\s*=\s*[`'"]([^`'"]+)[`'"]/;
var SEARCH_DATE_RE = /\d{4}-(\d{2})-(\d{2})/;
var IGNORE_IMAGE = ["icon-", "favicon", "192x192", "/toonfile/toonres/", "logo", "banner", "avatar"];

function enc(value) {
  return encodeURIComponent(String(value));
}

function trimWs(value) {
  return String(value == null ? "" : value).replace(/^\s+|\s+$/g, "");
}

function trimQuotes(value) {
  var s = trimWs(value);
  while (s.length && (s.charAt(0) === "'" || s.charAt(0) === '"')) s = s.substring(1);
  while (s.length && (s.charAt(s.length - 1) === "'" || s.charAt(s.length - 1) === '"')) {
    s = s.substring(0, s.length - 1);
  }
  return s;
}

function trimGenre(value) {
  return String(value).replace(/^[\s,·-]+|[\s,·-]+$/g, "");
}

function digitsOnly(value) {
  return String(value).replace(/\D/g, "");
}

function first(nodes) {
  return nodes && nodes.length ? nodes[0] : null;
}

function fail(message) {
  throw new Error(message);
}

function absUrl(raw, base) {
  var value = trimQuotes(raw);
  if (!value) return value;
  var root = base || ORIGIN;
  if (value.indexOf("https://") === 0 || value.indexOf("http://") === 0) return value;
  if (value.indexOf("//") === 0) return "https:" + value;
  if (value.charAt(0) === "/") return root.replace(/\/+$/, "") + value;
  return root + "/" + value;
}

function listingPath(catalogId, page) {
  var p = page < 1 ? 1 : page;
  if (catalogId === "LATEST") {
    return "/bbs/board.php?bo_table=toon_c&type=upd&tablename=" + enc("최신만화") + "&page=" + p;
  }
  if (catalogId === "POPULAR") {
    return "/bbs/board.php?bo_table=toon_c&tablename=" + enc("인기만화");
  }
  if (catalogId === "COMPLETE") {
    return "/bbs/board.php?bo_table=toon_c&is_over=1&tablename=" + enc("완결만화");
  }
  if (catalogId === "TODAY") {
    return "/bbs/board.php?bo_table=toon_c&type=today&tablename=" + enc("매일 추천 100");
  }
  fail("Unknown catalog: " + catalogId);
}

function catalogFromId(catalogId) {
  var key = String(catalogId || "").toUpperCase();
  if (key === "FAVORITE") fail("즐겨찾기는 로컬 목록입니다.");
  if (key !== "LATEST" && key !== "POPULAR" && key !== "COMPLETE" && key !== "TODAY") {
    fail("Unknown catalog: " + catalogId);
  }
  return key;
}

function listingUrl(catalogId, page) {
  return ORIGIN + listingPath(catalogFromId(catalogId), page);
}

function searchUrl(query) {
  return ORIGIN + "/bbs/ajax.search.php?search_key=" + enc(query);
}

function seriesUrl(id, title, page) {
  var p = page == null || page < 1 ? 1 : page;
  return ORIGIN + "/bbs/board.php?bo_table=toons&stx=" + enc(title) + "&is=" + id + "&page=" + p;
}

function coverUrl(toonId) {
  return COVER_HOST + "/data/toon_category/" + toonId + ".webp";
}

function extractBackgroundUrl(style) {
  if (!style) return null;
  var match = BG_URL_RE.exec(String(style));
  BG_URL_RE.lastIndex = 0;
  if (!match) return null;
  return trimQuotes(match[2]);
}

function pageFromHref(href) {
  if (!href) return null;
  var match = PAGE_RE.exec(href);
  if (!match) return null;
  var n = parseInt(match[1], 10);
  return isNaN(n) ? null : n;
}

function pageInfo(doc) {
  var currentEl = first(doc.select("strong.pg_current"));
  var current = 1;
  if (currentEl) {
    var parsed = parseInt(trimWs(currentEl.text()), 10);
    if (!isNaN(parsed)) current = parsed;
  }
  var last = current;
  var endEl = first(doc.select("a.pg_end"));
  if (endEl) {
    var fromEnd = pageFromHref(endEl.absUrl("href"));
    if (fromEnd != null && fromEnd > last) last = fromEnd;
  }
  var pages = doc.select("a.pg_page");
  for (var i = 0; i < pages.length; i++) {
    var fromPage = pageFromHref(pages[i].absUrl("href"));
    if (fromPage != null && fromPage > last) last = fromPage;
  }
  return { currentPage: current, lastPage: last };
}

function isImageUrl(url) {
  var lower = String(url).toLowerCase();
  return (
    lower.indexOf(".jpg") >= 0 ||
    lower.indexOf(".jpeg") >= 0 ||
    lower.indexOf(".png") >= 0 ||
    lower.indexOf(".webp") >= 0 ||
    lower.indexOf(".gif") >= 0
  );
}

function isComicImageUrl(url) {
  if (!isImageUrl(url)) return false;
  var lower = String(url).toLowerCase();
  for (var i = 0; i < IGNORE_IMAGE.length; i++) {
    if (lower.indexOf(IGNORE_IMAGE[i]) >= 0) return false;
  }
  return true;
}

function uniquePush(list, seen, value) {
  if (!value || seen[value]) return;
  seen[value] = true;
  list.push(value);
}

function parseToon(li, baseUri) {
  var id = trimWs(li.attr("data-id"));
  if (!id) return null;
  var titleEl = first(li.select(".homelist-title"));
  var title = titleEl ? trimWs(titleEl.text()) : "";
  if (!title) return null;
  var thumbEl = first(li.select(".homelist-thumb"));
  var rawThumb = null;
  if (thumbEl) {
    var mobile = trimWs(thumbEl.attr("data-mobile-image"));
    rawThumb = mobile || extractBackgroundUrl(thumbEl.attr("style"));
  }
  if (!rawThumb) rawThumb = coverUrl(id);
  var genreEl = first(li.select(".homelist-genre"));
  var genreRaw = genreEl ? trimWs(genreEl.text().replace(/\u00a0/g, " ")) : "";
  var dateMatch = DATE_RE.exec(genreRaw);
  DATE_RE.lastIndex = 0;
  var updatedAt = dateMatch ? dateMatch[1] : null;
  var genre = dateMatch ? trimGenre(genreRaw.replace(dateMatch[0], "")) : genreRaw;
  var link = first(li.select("a[href]"));
  var href = link ? link.absUrl("href") : "";
  if (!href) href = seriesUrl(id, title, 1);
  var rankingEl = first(li.select(".homelist-ranking"));
  var ranking = null;
  if (rankingEl) {
    ranking = digitsOnly(rankingEl.text()) || null;
  }
  return {
    id: id,
    title: title,
    thumbUrl: absUrl(rawThumb, baseUri),
    href: href,
    genre: genre,
    updatedAt: updatedAt,
    ranking: ranking
  };
}

function parseEpisode(button, baseUri) {
  var onclick = button.attr("onclick") || "";
  var wrMatch = WR_ID_RE.exec(onclick);
  WR_ID_RE.lastIndex = 0;
  var wrId = wrMatch ? wrMatch[1] : trimWs(button.attr("data-episode-id"));
  if (!wrId) return null;
  var titleEl = first(button.select(".episode-title"));
  var title = titleEl ? trimWs(titleEl.text()) : "";
  if (!title) return null;
  var dateEl = first(button.select(".free-date"));
  var dateText = dateEl ? dateEl.text().replace(/\u00a0/g, " ") : "";
  var fullMatch = FULL_DATE_RE.exec(dateText);
  FULL_DATE_RE.lastIndex = 0;
  var date = null;
  if (fullMatch) {
    date = fullMatch[1];
  } else {
    var shortMatch = DATE_RE.exec(dateText);
    DATE_RE.lastIndex = 0;
    if (shortMatch) date = shortMatch[1];
  }
  var banner = first(button.select(".episode-banner"));
  var rawThumb = banner ? extractBackgroundUrl(banner.attr("style")) : null;
  var hrefMatch = HREF_RE.exec(onclick);
  var href;
  if (hrefMatch) {
    var cleaned = hrefMatch[1];
    if (cleaned.indexOf("./") === 0) cleaned = cleaned.substring(2);
    var path = cleaned.indexOf("board.php") === 0 ? "/bbs/" + cleaned : cleaned;
    href = absUrl(path, baseUri);
  } else {
    href = absUrl("/bbs/board.php?bo_table=toons&wr_id=" + wrId, baseUri);
  }
  return {
    wrId: wrId,
    title: title,
    date: date,
    thumbUrl: rawThumb ? absUrl(rawThumb, baseUri) : null,
    href: href
  };
}

function parseListing(html, baseUri) {
  var doc = host.parseHtml(html, baseUri);
  var nodes = doc.select("li[data-id]");
  var items = [];
  var seen = {};
  for (var i = 0; i < nodes.length; i++) {
    var item = parseToon(nodes[i], baseUri);
    if (!item || seen[item.id]) continue;
    seen[item.id] = true;
    items.push(item);
  }
  var pages = pageInfo(doc);
  return { items: items, currentPage: pages.currentPage, lastPage: pages.lastPage };
}

function parseEpisodes(html, baseUri) {
  var doc = host.parseHtml(html, baseUri);
  var nodes = doc.select("button.episode.is-series");
  var items = [];
  var seen = {};
  for (var i = 0; i < nodes.length; i++) {
    var item = parseEpisode(nodes[i], baseUri);
    if (!item || seen[item.wrId]) continue;
    seen[item.wrId] = true;
    items.push(item);
  }
  var pages = pageInfo(doc);
  return { items: items, currentPage: pages.currentPage, lastPage: pages.lastPage };
}

function parseAllImageCandidateLists(html, baseUri) {
  var result = [];
  JS_IMG_ARRAY_RE.lastIndex = 0;
  var match;
  while ((match = JS_IMG_ARRAY_RE.exec(html)) !== null) {
    var body = match[1];
    var urls = [];
    var seen = {};
    JS_STRING_URL_RE.lastIndex = 0;
    var urlMatch;
    while ((urlMatch = JS_STRING_URL_RE.exec(body)) !== null) {
      var rawUrl = trimWs(urlMatch[1]);
      if (rawUrl && isImageUrl(rawUrl)) uniquePush(urls, seen, absUrl(rawUrl, baseUri));
    }
    if (urls.length) result.push(urls);
  }
  return result;
}

function parseImagesFromDom(html, baseUri) {
  var doc = host.parseHtml(html, baseUri);
  var containers = doc.select("#scroll-list, .view-content, .scroll-viewer, #page-list, #contents-wrapper");
  var useDoc = containers.length === 0;
  var imgNodes = useDoc ? doc.select("img") : containers[0].select("img");
  var bgNodes = useDoc ? doc.select("[style*='background']") : containers[0].select("[style*='background']");
  var urls = [];
  var seen = {};
  for (var i = 0; i < imgNodes.length; i++) {
    var img = imgNodes[i];
    var url;
    if (img.attr("data-src")) url = img.absUrl("data-src");
    else if (img.attr("data-original")) url = img.absUrl("data-original");
    else if (img.attr("data-lazy")) url = img.absUrl("data-lazy");
    else url = img.absUrl("src");
    if (url && isComicImageUrl(url)) uniquePush(urls, seen, url);
  }
  for (var j = 0; j < bgNodes.length; j++) {
    var bg = extractBackgroundUrl(bgNodes[j].attr("style"));
    if (!bg) continue;
    var absBg = absUrl(bg, baseUri);
    if (isComicImageUrl(absBg)) uniquePush(urls, seen, absBg);
  }
  return urls;
}

function parseImages(html, baseUri) {
  var candidateLists = parseAllImageCandidateLists(html, baseUri);
  if (candidateLists.length) return candidateLists[0];
  return parseImagesFromDom(html, baseUri);
}

function formatDate(raw) {
  if (!raw) return null;
  var match = SEARCH_DATE_RE.exec(String(raw));
  if (!match) return null;
  return match[1] + "." + match[2];
}

function getFallbackImageUrls(url) {
  if (!url) return [];
  var fallbacks = [];
  var seen = {};
  for (var i = 0; i < KNOWN_DOMAINS.length; i++) {
    var domain = KNOWN_DOMAINS[i];
    if (url.indexOf(domain) >= 0) continue;
    var replaced = url.replace(/pl\d{4}\.com/g, domain);
    if (replaced === url) continue;
    uniquePush(fallbacks, seen, replaced);
    var withParam;
    if (replaced.indexOf("?") >= 0) {
      withParam = replaced.indexOf("v=ei") >= 0 ? replaced : replaced + "&v=ei";
    } else {
      withParam = replaced + "?v=ei";
    }
    uniquePush(fallbacks, seen, withParam);
  }
  return fallbacks;
}

var source = {
  id: "eleven",
  displayName: "11toon",
  apiLevel: 1,
  origin: ORIGIN,
  catalogs: [
    { id: "LATEST", label: "최신", paginated: true },
    { id: "POPULAR", label: "인기", paginated: false },
    { id: "COMPLETE", label: "완결", paginated: false },
    { id: "TODAY", label: "오늘", paginated: false }
  ],
  searchPlaceholder: "제목 검색",
  notificationMode: "LATEST_INTERSECTION",
  episodePageSize: 100,
  emptyListingOk: false,
  emptyEpisodesOk: false,
  defaultLanguage: null,
  progressDisplay: "LAST_READ_ORDER",
  userAgent: null,
  referer: null,
  extraHeaders: {
    Accept: "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8"
  },

  loadListing: function (catalogId, page) {
    var html = host.fetchText({ url: listingUrl(catalogId, page) });
    return parseListing(html, ORIGIN);
  },

  search: function (query) {
    var q = trimWs(query && query.text);
    if (!q) return [];
    var json = host.fetchText({ url: searchUrl(q) });
    var root = host.json(json);
    if (!root || root.status !== "success" || !root.list) return [];
    var list = root.list;
    var items = [];
    var seen = {};
    for (var i = 0; i < list.length; i++) {
      var row = list[i];
      if (!row) continue;
      var id = trimWs(row.wr_id);
      var title = trimWs(row.wr_subject);
      if (!id || !title) continue;
      if (seen[id]) continue;
      seen[id] = true;
      items.push({
        id: id,
        title: title,
        thumbUrl: coverUrl(id),
        href: seriesUrl(id, title, 1),
        genre: trimWs(row.ca_name),
        updatedAt: formatDate(row.wr_datetime)
      });
    }
    return items;
  },

  suggest: function (query) {
    return [];
  },

  loadEpisodes: function (item, page) {
    var html = host.fetchText({ url: seriesUrl(item.id, item.title, page) });
    return parseEpisodes(html, ORIGIN);
  },

  resolveImages: function (episode, item) {
    var html = host.fetchText({ url: episode.href });
    var candidateLists = parseAllImageCandidateLists(html, ORIGIN);
    if (!candidateLists.length) {
      var parsed = parseImages(html, ORIGIN);
      if (!parsed.length) fail("만화 이미지를 불러오지 못했습니다.");
      return parsed;
    }
    if (candidateLists.length === 1) return candidateLists[0];
    var primary = candidateLists[0];
    if (host.isAccessible(primary.length ? primary[0] : "")) return primary;
    for (var i = 1; i < candidateLists.length; i++) {
      var candidate = candidateLists[i];
      if (host.isAccessible(candidate.length ? candidate[0] : "")) return candidate;
    }
    return primary;
  },

  imageFallbacks: function (url) {
    return getFallbackImageUrls(url);
  },

  imageReferer: function (url) {
    return ORIGIN;
  },

  coverUrl: function (toonId) {
    return coverUrl(toonId);
  },

  ownsHost: function (hostName) {
    var h = String(hostName || "").toLowerCase();
    if (ELEVEN_HOST.test(h) || PL_HOST.test(h)) return true;
    return h === ORIGIN_HOST;
  },

  useProxy: function (url) {
    return true;
  },

  resolveParent: function (item, choice, entryEpisodeId) {
    return null;
  },

  supportsChapterNotifications: function (item) {
    return true;
  },

  notificationCandidates: function (favorites) {
    return favorites;
  },

  applyConfig: function (config) {}
};
