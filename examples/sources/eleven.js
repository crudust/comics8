// Sample 11toon parser for in-app import. Not bundled in the APK or server.
var ORIGIN = "http://103.204.13.68:8904";
var COVER_HOST = "https://11toon8.com";
var KNOWN_DOMAINS = [
  "pl4050.com",
  "pl3040.com",
  "pl3030.com",
  "pl4040.com",
  "pl1020.com",
  "pl2030.com",
  "pl5060.com"
];

function seriesUrl(id, title, page) {
  var p = page == null || page < 1 ? 1 : page;
  return ORIGIN + "/bbs/board.php?bo_table=toons&stx=" + encodeURIComponent(title) + "&is=" + id + "&page=" + p;
}

function coverUrl(toonId) {
  return COVER_HOST + "/data/toon_category/" + toonId + ".webp";
}

function listingPath(catalogId, page) {
  var cat = String(catalogId || "").toUpperCase();
  if (cat === "FAVORITE") throw new Error("즐겨찾기는 로컬 목록입니다.");
  if (cat === "LATEST") {
    var p = page < 1 ? 1 : page;
    return "/bbs/board.php?bo_table=toon_c&type=upd&tablename=" + encodeURIComponent("최신만화") + "&page=" + p;
  }
  if (cat === "POPULAR") return "/bbs/board.php?bo_table=toon_c&tablename=" + encodeURIComponent("인기만화");
  if (cat === "COMPLETE") return "/bbs/board.php?bo_table=toon_c&is_over=1&tablename=" + encodeURIComponent("완결만화");
  if (cat === "TODAY") return "/bbs/board.php?bo_table=toon_c&type=today&tablename=" + encodeURIComponent("매일 추천 100");
  throw new Error("Unknown catalog: " + catalogId);
}

var source = {
  id: "eleven",
  displayName: "11toon",
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
  extraHeaders: {
    "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8"
  },

  loadListing: function (catalogId, page) {
    var path = listingPath(catalogId, page);
    var doc = host.parseHtml(host.fetchText({ url: ORIGIN + path }), ORIGIN);
    var nodes = doc.select("li[data-id]");
    var items = [];
    var seen = {};

    for (var i = 0; i < nodes.length; i++) {
      var li = nodes[i];
      var id = li.attr("data-id").trim();
      var title = li.textOf(".homelist-title").trim();
      if (!id || !title || seen[id]) continue;
      seen[id] = true;

      var thumbEl = li.selectFirst(".homelist-thumb");
      var rawThumb = thumbEl ? (thumbEl.attr("data-mobile-image").trim() || thumbEl.bgUrl()) : null;
      var genreRaw = li.textOf(".homelist-genre").replace(/\u00a0/g, " ").trim();
      var updatedAt = host.match(genreRaw, "(\\d{2}\\.\\d{2})", 1);
      var genre = updatedAt ? genreRaw.replace(updatedAt, "").replace(/^[\s,·-]+|[\s,·-]+$/g, "") : genreRaw;
      var ranking = host.digits(li.textOf(".homelist-ranking")) || null;

      items.push({
        id: id,
        title: title,
        thumbUrl: host.absUrl(rawThumb || coverUrl(id), ORIGIN),
        href: seriesUrl(id, title, 1),
        genre: genre,
        updatedAt: updatedAt,
        ranking: ranking
      });
    }

    return { items: items, pageInfo: host.parsePageInfo(doc) };
  },

  search: function (query) {
    var q = (query && query.text || "").trim();
    if (!q) return [];
    var root = host.fetchJson({ url: ORIGIN + "/bbs/ajax.search.php?search_key=" + encodeURIComponent(q) });
    if (!root || root.status !== "success" || !root.list) return [];
    var list = root.list;
    var items = [];
    var seen = {};

    for (var i = 0; i < list.length; i++) {
      var row = list[i];
      if (!row) continue;
      var id = String(row.wr_id || "").trim();
      var title = String(row.wr_subject || "").trim();
      if (!id || !title || seen[id]) continue;
      seen[id] = true;

      var date = host.match(row.wr_datetime, "\\d{4}-(\\d{2}-\\d{2})", 1);
      items.push({
        id: id,
        title: title,
        thumbUrl: coverUrl(id),
        href: seriesUrl(id, title, 1),
        genre: String(row.ca_name || "").trim(),
        updatedAt: date ? date.replace("-", ".") : null
      });
    }
    return items;
  },

  loadEpisodes: function (item, page) {
    var doc = host.parseHtml(host.fetchText({ url: seriesUrl(item.id, item.title, page) }), ORIGIN);
    var nodes = doc.select("button.episode.is-series");
    var items = [];
    var seen = {};

    for (var i = 0; i < nodes.length; i++) {
      var btn = nodes[i];
      var onclick = btn.attr("onclick");
      var wrId = host.match(onclick, "wr_id=(\\d+)", 1) || btn.attr("data-episode-id").trim();
      var title = btn.textOf(".episode-title").trim();
      if (!wrId || !title || seen[wrId]) continue;
      seen[wrId] = true;

      var dateText = btn.textOf(".free-date").replace(/\u00a0/g, " ");
      var date = host.match(dateText, "(\\d{2}\\.\\d{2}\\.\\d{2})", 1) || host.match(dateText, "(\\d{2}\\.\\d{2})", 1);
      var banner = btn.selectFirst(".episode-banner");
      var rawThumb = banner ? banner.bgUrl() : null;

      var hrefRel = host.match(onclick, "location\\.href\\s*=\\s*[`'\"]([^`'\"]+)[`'\"]", 1);
      var href = hrefRel
        ? host.absUrl(hrefRel.replace(/^\.\//, "").replace(/^board\.php/, "/bbs/board.php"), ORIGIN)
        : host.absUrl("/bbs/board.php?bo_table=toons&wr_id=" + wrId, ORIGIN);

      items.push({
        wrId: wrId,
        title: title,
        date: date,
        thumbUrl: rawThumb ? host.absUrl(rawThumb, ORIGIN) : null,
        href: href
      });
    }

    return { items: items, pageInfo: host.parsePageInfo(doc) };
  },

  resolveImages: function (episode) {
    return host.extractImages(host.fetchText({ url: episode.href }), ORIGIN);
  },

  imageFallbacks: function (url) {
    if (!url) return [];
    var fallbacks = [];
    var seen = {};
    for (var i = 0; i < KNOWN_DOMAINS.length; i++) {
      var domain = KNOWN_DOMAINS[i];
      if (url.indexOf(domain) >= 0) continue;
      var replaced = url.replace(/pl\d{4}\.com/g, domain);
      if (replaced === url || seen[replaced]) continue;
      seen[replaced] = true;
      fallbacks.push(replaced);
      var withParam = replaced + (replaced.indexOf("?") >= 0 ? "&v=ei" : "?v=ei");
      if (!seen[withParam]) {
        seen[withParam] = true;
        fallbacks.push(withParam);
      }
    }
    return fallbacks;
  },

  ownsHost: function (h) {
    var hostName = String(h || "").toLowerCase();
    return /^(www\.)?11toon\d*\.com$/.test(hostName) || /^(www\.)?pl\d{4}\.com$/.test(hostName) || hostName === "103.204.13.68";
  },

  coverUrl: coverUrl
};
