var source;

(function () {
  var ORIGIN = "https://hitomi.la";
  var REFERER = "https://hitomi.la/";
  var LTN = "https://ltn.gold-usergeneratedcontent.net";
  var CDN_HOST = "gold-usergeneratedcontent.net";
  var TAGINDEX = "https://tagindex.hitomi.la";
  var DEFAULT_LANGUAGE = "korean";
  var PAGE_SIZE = 25;
  var ID_BYTES = 4;
  var GALLERY_CONCURRENCY = 6;
  var GG_TTL_MS = 10 * 60 * 1000;
  var GALLERY_TTL_MS = 10 * 60 * 1000;
  var GALLERY_CACHE_MAX = 200;
  var ARTIST_PREFIX = "artist:";
  var GALLERY_PREFIX = "gallery:";

  var NAMESPACES = {
    language: true,
    type: true,
    female: true,
    male: true,
    tag: true,
    artist: true,
    series: true,
    character: true,
    group: true,
  };
  var SERIES_NAMESPACES = {
    artist: true,
    series: true,
    character: true,
    group: true,
  };
  var LANG_SUFFIX = /-(all|korean|english|japanese|chinese|spanish|french|russian|indonesian|vietnamese|thai|italian|german|polish)$/i;

  var language = DEFAULT_LANGUAGE;
  var ggCache = null;
  var galleryCache = {};
  var galleryCacheOrder = [];

  function trim(s) {
    return String(s == null ? "" : s).replace(/^\s+|\s+$/g, "");
  }

  function lower(s) {
    return String(s == null ? "" : s).toLowerCase();
  }

  function startsWith(s, prefix) {
    s = String(s == null ? "" : s);
    return s.substring(0, prefix.length) === prefix;
  }

  function endsWith(s, suffix) {
    s = String(s == null ? "" : s);
    suffix = String(suffix);
    return s.length >= suffix.length && s.substring(s.length - suffix.length) === suffix;
  }

  function encodePath(value) {
    return encodeURIComponent(value).replace(/!/g, "%21").replace(/'/g, "%27").replace(/\(/g, "%28").replace(/\)/g, "%29");
  }

  function currentLanguage() {
    if (host.language != null && host.language !== "") return host.language;
    return language;
  }

  function normalizeSlug(raw) {
    return trim(raw).toLowerCase().replace(/\s+/g, "_");
  }

  function forIndexPath(raw) {
    return normalizeSlug(raw).replace(/_/g, " ");
  }

  function searchSlug(raw) {
    return trim(raw).toLowerCase().replace(/_/g, " ").replace(/\s+/g, "_").replace(/^_+|_+$/g, "");
  }

  function fromArtistUrl(url) {
    if (url == null || url === "") return null;
    var m = /\/artist\/([^/?#]+)/i.exec(String(url));
    if (!m) return null;
    var decoded;
    try {
      decoded = decodeURIComponent(m[1].replace(/\+/g, " "));
    } catch (e) {
      decoded = m[1];
    }
    decoded = decoded.replace(/\.html$/i, "");
    decoded = decoded.replace(LANG_SUFFIX, "");
    decoded = trim(decoded);
    return decoded ? normalizeSlug(decoded) : null;
  }

  function indexNozomi(lang) {
    var key = trim(lang) || DEFAULT_LANGUAGE;
    if (key.toLowerCase() === "all") return LTN + "/index-all.nozomi";
    return LTN + "/index-" + key + ".nozomi";
  }

  function typeNozomi(type, lang) {
    return LTN + "/" + type + "-" + (trim(lang) || DEFAULT_LANGUAGE) + ".nozomi";
  }

  function popularNozomi(period, lang) {
    return LTN + "/popular/" + period + "-" + (trim(lang) || DEFAULT_LANGUAGE) + ".nozomi";
  }

  function nsNozomi(ns, slug, lang) {
    return LTN + "/" + ns + "/" + encodePath(forIndexPath(slug)) + "-" + (trim(lang) || DEFAULT_LANGUAGE) + ".nozomi";
  }

  function galleryJs(id) {
    return LTN + "/galleries/" + id + ".js";
  }

  function ggJsUrl() {
    return LTN + "/gg.js";
  }

  function readerUrl(id) {
    return ORIGIN + "/reader/" + id + ".html";
  }

  function galleryPage(id) {
    return ORIGIN + "/galleries/" + id + ".html";
  }

  function nsPage(ns, slug) {
    return ORIGIN + "/" + ns + "/" + encodePath(forIndexPath(slug)) + "-all.html";
  }

  function rangeHeader(page) {
    var p = Math.max(1, page | 0);
    var start = (p - 1) * PAGE_SIZE * ID_BYTES;
    var end = p * PAGE_SIZE * ID_BYTES - 1;
    return "bytes=" + start + "-" + end;
  }

  function pathFromHash(hash) {
    var last3 = String(hash).substring(String(hash).length - 3);
    if (last3.length < 3) return "";
    return last3.charAt(2) + "/" + last3.charAt(0) + last3.charAt(1);
  }

  function thumbUrl(hash) {
    if (!hash || String(hash).length < 3) return "";
    var s = pathFromHash(hash);
    return "https://tn." + CDN_HOST + "/webpsmalltn/" + s + "/" + hash + ".webp";
  }

  function sFromHash(gg, hash) {
    if (gg && typeof gg.s === "function") return String(gg.s(hash));
    var last3 = String(hash).substring(String(hash).length - 3);
    if (last3.length < 3) return "0";
    return String(parseInt(last3.charAt(2) + last3.charAt(0) + last3.charAt(1), 16) || 0);
  }

  function imageUrl(hash, b, n, ext, gg) {
    var hostPrefix = lower(ext) === "avif" ? "a" : "w";
    var s = sFromHash(gg, hash);
    var prefix = !b ? "" : endsWith(b, "/") ? b : b + "/";
    return "https://" + hostPrefix + n + "." + CDN_HOST + "/" + prefix + s + "/" + hash + "." + ext;
  }

  function altImageUrl(url) {
    if (String(url).indexOf(CDN_HOST) < 0) return null;
    if (url.indexOf("://w") >= 0 && endsWith(url, ".webp")) {
      return url.replace(/:\/\/w(\d+)\./, "://a$1.").replace(/\.webp$/, ".avif");
    }
    if (url.indexOf("://a") >= 0 && endsWith(url, ".avif")) {
      return url.replace(/:\/\/a(\d+)\./, "://w$1.").replace(/\.avif$/, ".webp");
    }
    return null;
  }

  function thumbFallbacks(url) {
    if (!url) return [];
    var out = [];
    var seen = {};
    function add(u) {
      if (!u || u === url || seen[u]) return;
      seen[u] = true;
      out.push(u);
    }
    var rewritten = String(url)
      .split("://tn.hitomi.la/").join("://tn." + CDN_HOST + "/")
      .split("://atn.hitomi.la/").join("://atn." + CDN_HOST + "/");
    add(rewritten);
    var hostSwap = rewritten
      .split("://tn." + CDN_HOST + "/").join("://atn." + CDN_HOST + "/")
      .split("://tn.hitomi.la/").join("://atn." + CDN_HOST + "/");
    add(hostSwap);
    if (rewritten.indexOf("/webpsmalltn/") >= 0 && endsWith(rewritten, ".webp")) {
      add(rewritten.split("/webpsmalltn/").join("/avifsmalltn/").replace(/\.webp$/, ".avif"));
      add(rewritten.split("/webpsmalltn/").join("/webpbigtn/"));
    }
    return out;
  }

  function parseIds(body) {
    var len = host.byteLength(body);
    var ids = [];
    var i = 0;
    while (i + ID_BYTES <= len) {
      ids.push(host.u32be(body, i));
      i += ID_BYTES;
    }
    return ids;
  }

  function lastPage(page, ids, totalLength) {
    var current = Math.max(1, page | 0);
    if (totalLength != null && totalLength !== "" && totalLength > 0) {
      var idCount = Math.floor(totalLength / ID_BYTES);
      var pages = Math.floor((idCount + PAGE_SIZE - 1) / PAGE_SIZE);
      return Math.max(1, pages);
    }
    return ids.length < PAGE_SIZE ? current : current + 1;
  }

  function fetchNozomiPage(url, page) {
    var result = host.fetch({
      url: url,
      headers: { Range: rangeHeader(page) },
    });
    if (result.code < 200 || result.code > 299) {
      throw new Error("HTTP " + result.code);
    }
    return result;
  }

  function listingNozomi(catalogId) {
    var lang = currentLanguage();
    switch (String(catalogId).toUpperCase()) {
      case "LATEST":
      case "INDEX":
        return indexNozomi(lang);
      case "POPULAR":
        return popularNozomi("week", lang);
      case "TODAY":
        return popularNozomi("today", lang);
      case "MONTH":
        return popularNozomi("month", lang);
      case "YEAR":
        return popularNozomi("year", lang);
      case "DOUJINSHI":
        return typeNozomi("doujinshi", lang);
      case "MANGA":
        return typeNozomi("manga", lang);
      case "ARTISTCG":
        return typeNozomi("artistcg", lang);
      case "GAMECG":
        return typeNozomi("gamecg", lang);
      case "IMAGESET":
        return typeNozomi("imageset", lang);
      default:
        throw new Error("Unknown catalog: " + catalogId);
    }
  }

  function parseSearch(text) {
    var normalized = trim(text).replace(
      /(language|type|female|male|tag|artist|series|character|group):\s+/gi,
      function (_, ns) {
        return ns.toLowerCase() + ":";
      },
    );
    if (!normalized) return [];
    var parts = normalized.split(/\s+/);
    var tokens = [];
    var bare = [];
    function flushBare() {
      if (!bare.length) return;
      tokens.push({ ns: "artist", value: searchSlug(bare.join(" ")) });
      bare = [];
    }
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (!part) continue;
      if (part.charAt(0) === "-") continue;
      var idx = part.indexOf(":");
      if (idx > 0) {
        var ns = part.substring(0, idx).toLowerCase();
        var value = searchSlug(part.substring(idx + 1));
        if (NAMESPACES[ns] && value) {
          flushBare();
          tokens.push({ ns: ns, value: value });
          continue;
        }
      }
      bare.push(part);
    }
    flushBare();
    return tokens;
  }

  function nozomiUrlForToken(token, lang) {
    var slug = normalizeSlug(token.value);
    if (token.ns === "language") return indexNozomi(normalizeSlug(token.value));
    if (token.ns === "type") return typeNozomi(slug, lang);
    return nsNozomi(token.ns, slug, lang);
  }

  function NozomiCursor(url) {
    this.url = url;
    this.page = 1;
    this.lastPage = 2147483647;
    this.buffer = [];
  }

  NozomiCursor.prototype.peek = function () {
    while (this.buffer.length === 0 && this.page <= this.lastPage) {
      var result = fetchNozomiPage(this.url, this.page);
      var ids = parseIds(result.body);
      this.lastPage = lastPage(this.page, ids, result.totalLength());
      for (var i = 0; i < ids.length; i++) this.buffer.push(ids[i]);
      this.page += 1;
      if (ids.length === 0) break;
    }
    return this.buffer.length ? this.buffer[0] : null;
  };

  NozomiCursor.prototype.pop = function () {
    if (this.buffer.length) this.buffer.shift();
  };

  function collectSearchIds(urls) {
    if (!urls.length) return [];
    var cursors = [];
    var i;
    for (i = 0; i < urls.length; i++) cursors.push(new NozomiCursor(urls[i]));
    var out = [];
    while (out.length < PAGE_SIZE) {
      var heads = [];
      for (i = 0; i < cursors.length; i++) heads.push(cursors[i].peek());
      var hasNull = false;
      for (i = 0; i < heads.length; i++) {
        if (heads[i] == null) {
          hasNull = true;
          break;
        }
      }
      if (hasNull) break;
      var target = heads[0];
      for (i = 1; i < heads.length; i++) {
        if (heads[i] < target) target = heads[i];
      }
      var allMatch = true;
      for (i = 0; i < heads.length; i++) {
        if (heads[i] !== target) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) {
        out.push(target);
        for (i = 0; i < cursors.length; i++) cursors[i].pop();
      } else {
        for (i = 0; i < cursors.length; i++) {
          var head = cursors[i].peek();
          if (head == null) return out;
          if (head > target) cursors[i].pop();
        }
      }
    }
    return out;
  }

  function parseArtists(raw) {
    if (!raw || raw.length == null) return [];
    var out = [];
    var seen = {};
    for (var i = 0; i < raw.length; i++) {
      var entry = raw[i];
      if (entry == null) continue;
      var name;
      var url = null;
      if (typeof entry === "object") {
        name = entry.artist || entry.name || "";
        url = entry.url || null;
      } else {
        name = String(entry);
      }
      name = trim(name);
      if (!name) continue;
      var slug = fromArtistUrl(url) || normalizeSlug(name);
      if (seen[slug]) continue;
      seen[slug] = true;
      out.push({ slug: slug, displayName: name });
    }
    return out;
  }

  function parseFiles(raw) {
    if (!raw || raw.length == null) return [];
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      var file = raw[i];
      if (!file) continue;
      var hash = trim(file.hash);
      if (!hash) continue;
      out.push({
        hash: hash,
        hasWebp: file.haswebp == null ? true : file.haswebp != 0,
        hasAvif: file.hasavif == null ? false : file.hasavif != 0,
      });
    }
    return out;
  }

  function formatDate(raw) {
    if (raw == null || raw === "") return null;
    var m = /(\d{4})-(\d{2})-(\d{2})/.exec(String(raw));
    if (!m) return null;
    return m[1].substring(m[1].length - 2) + "." + m[2] + "." + m[3];
  }

  function parseGalleryInfo(text) {
    var obj;
    try {
      obj = host.evalSiteJs("galleryinfo", text);
    } catch (e) {
      return null;
    }
    if (!obj) return null;
    var id = obj.id == null ? "" : trim(String(obj.id));
    if (!id || id === "null") return null;
    var title = trim(obj.title) || id;
    var type = trim(obj.type);
    var dateRaw = obj.date ? String(obj.date) : null;
    return {
      id: id,
      title: title,
      type: type,
      dateRaw: dateRaw,
      updatedAt: formatDate(dateRaw),
      artists: parseArtists(obj.artists),
      files: parseFiles(obj.files),
    };
  }

  function cachedGallery(id) {
    var cached = galleryCache[id];
    if (!cached) return null;
    if (Date.now() - cached.loadedAt >= GALLERY_TTL_MS) {
      delete galleryCache[id];
      return null;
    }
    return cached.info;
  }

  function rememberGallery(info) {
    if (galleryCache[info.id]) {
      galleryCache[info.id] = { loadedAt: Date.now(), info: info };
      return;
    }
    while (galleryCacheOrder.length >= GALLERY_CACHE_MAX) {
      var evict = galleryCacheOrder.shift();
      delete galleryCache[evict];
    }
    galleryCache[info.id] = { loadedAt: Date.now(), info: info };
    galleryCacheOrder.push(info.id);
  }

  function loadGg() {
    var now = Date.now();
    if (ggCache && now - ggCache.loadedAt < GG_TTL_MS && ggCache.gg && ggCache.gg.b) {
      return ggCache.gg;
    }
    var result = host.fetch({ url: ggJsUrl() });
    if (result.code < 200 || result.code > 299) {
      throw new Error("만화 이미지를 불러오지 못했습니다.");
    }
    var gg = host.evalSiteJs("gg", host.utf8(result.body));
    if (!gg || !gg.b) {
      throw new Error("만화 이미지를 불러오지 못했습니다.");
    }
    ggCache = { loadedAt: Date.now(), gg: gg };
    return gg;
  }

  function fetchGalleryInfo(id) {
    var cached = cachedGallery(id);
    if (cached) return cached;
    var result = host.fetch({ url: galleryJs(id) });
    if (result.code < 200 || result.code > 299) return null;
    var parsed = parseGalleryInfo(host.utf8(result.body));
    if (parsed) rememberGallery(parsed);
    return parsed;
  }

  function loadGalleryInfos(ids) {
    if (!ids.length) return [];
    var slots = [];
    var missing = [];
    var missingIdx = [];
    var i;
    for (i = 0; i < ids.length; i++) {
      var sid = String(ids[i]);
      var cached = cachedGallery(sid);
      if (cached) {
        slots[i] = cached;
      } else {
        missing.push(sid);
        missingIdx.push(i);
      }
    }
    if (missing.length) {
      var specs = [];
      for (i = 0; i < missing.length; i++) {
        specs.push({ url: galleryJs(missing[i]) });
      }
      var results = host.fetchAll(specs, GALLERY_CONCURRENCY);
      for (i = 0; i < results.length; i++) {
        var r = results[i];
        if (r.code < 200 || r.code > 299) continue;
        var info = parseGalleryInfo(host.utf8(r.body));
        if (!info) continue;
        rememberGallery(info);
        slots[missingIdx[i]] = info;
      }
    }
    var out = [];
    var seen = {};
    for (i = 0; i < slots.length; i++) {
      var gallery = slots[i];
      if (!gallery) continue;
      if (lower(gallery.type) === "anime") continue;
      if (seen[gallery.id]) continue;
      seen[gallery.id] = true;
      out.push(gallery);
    }
    return out;
  }

  function listingKey(item) {
    if (item.entryEpisodeId) return "hitomi:g:" + item.entryEpisodeId;
    return "hitomi:" + item.id;
  }

  function toListingItem(info) {
    if (lower(info.type) === "anime") return null;
    var artists = info.artists;
    var toonId;
    var choices;
    var title;
    if (!artists.length) {
      toonId = GALLERY_PREFIX + info.id;
      choices = [];
      title = info.title;
    } else if (artists.length === 1) {
      toonId = ARTIST_PREFIX + artists[0].slug;
      choices = [];
      title = artists[0].displayName;
    } else {
      toonId = GALLERY_PREFIX + info.id;
      choices = artists;
      title = info.title;
    }
    var first = info.files.length ? info.files[0] : null;
    return {
      id: toonId,
      title: title,
      thumbUrl: first ? thumbUrl(first.hash) : "",
      href: galleryPage(info.id),
      genre: info.type,
      updatedAt: info.updatedAt,
      entryEpisodeId: info.id,
      artistChoices: choices,
    };
  }

  function loadGalleryCards(ids) {
    var infos = loadGalleryInfos(ids);
    var items = [];
    var seen = {};
    for (var i = 0; i < infos.length; i++) {
      var item = toListingItem(infos[i]);
      if (!item) continue;
      var key = listingKey(item);
      if (seen[key]) continue;
      seen[key] = true;
      items.push(item);
    }
    return items;
  }

  function toEpisodeItem(info) {
    var first = info.files.length ? info.files[0] : null;
    return {
      wrId: info.id,
      title: info.title,
      date: info.updatedAt,
      thumbUrl: first ? thumbUrl(first.hash) : null,
      href: readerUrl(info.id),
      artistChoices: info.artists.length >= 2 ? info.artists : [],
    };
  }

  function nsSlugOf(toonId) {
    var idx = String(toonId).indexOf(":");
    if (idx <= 0) return null;
    var ns = toonId.substring(0, idx);
    var slug = toonId.substring(idx + 1);
    if (!SERIES_NAMESPACES[ns] || !slug) return null;
    return { ns: ns, slug: slug };
  }

  function galleryIdOf(item) {
    if (item.entryEpisodeId) return item.entryEpisodeId;
    var toonId = String(item.id || "");
    if (startsWith(toonId, GALLERY_PREFIX)) {
      var rest = toonId.substring(GALLERY_PREFIX.length);
      return rest || null;
    }
    return /^\d+$/.test(toonId) ? toonId : null;
  }

  function nsSeriesItem(ns, slug, lang) {
    var cover = null;
    try {
      var ids = parseIds(fetchNozomiPage(nsNozomi(ns, slug, lang), 1).body);
      if (ids.length) cover = fetchGalleryInfo(String(ids[0]));
    } catch (e) {
      cover = null;
    }
    var first = cover && cover.files.length ? cover.files[0] : null;
    return {
      id: ns + ":" + slug,
      title: slug.split("_").join(" "),
      thumbUrl: first ? thumbUrl(first.hash) : "",
      href: nsPage(ns, slug),
      genre: cover && cover.type ? cover.type : ns,
      updatedAt: cover ? cover.updatedAt : null,
      entryEpisodeId: cover ? cover.id : null,
    };
  }

  function lastTerm(query) {
    var trimmed = String(query || "").replace(/\s+$/g, "");
    var parts = trimmed.split(/\s+/);
    var last = String(parts[parts.length - 1] || "");
    if (startsWith(last, "-")) last = last.substring(1);
    var idx = last.indexOf(":");
    if (idx > 0) {
      var ns = last.substring(0, idx).toLowerCase();
      var term = last.substring(idx + 1).split("_").join(" ");
      if (NAMESPACES[ns]) return { ns: ns, term: term };
    }
    return { ns: "artist", term: last.split("_").join(" ") };
  }

  function suggestUrl(query) {
    if (!trim(query)) return null;
    var lt = lastTerm(query);
    if (!lt.term) return null;
    var path = "";
    var term = lt.term.toLowerCase();
    for (var i = 0; i < term.length; i++) {
      var ch = term.charAt(i);
      var enc = ch === " " ? "_" : ch === "/" ? "slash" : ch === "." ? "dot" : ch;
      if (i > 0) path += "/";
      path += enc;
    }
    return TAGINDEX + "/" + lt.ns + "/" + path + ".json";
  }

  function parseSuggest(body) {
    var root;
    try {
      root = host.json(body);
    } catch (e) {
      return [];
    }
    if (!root || root.length == null) return [];
    var out = [];
    for (var i = 0; i < root.length && out.length < 10; i++) {
      var row = root[i];
      if (!row || row.length == null) continue;
      var tag = trim(row[0]);
      var ns = trim(row[2]) || "artist";
      if (!tag) continue;
      var count = parseInt(row[1], 10);
      if (isNaN(count)) count = 0;
      out.push({ ns: ns, tag: tag, count: count });
    }
    return out;
  }

  function subdomainIndex(gg, hash) {
    var g;
    if (typeof gg.s === "function") {
      g = parseInt(gg.s(hash), 10);
    } else {
      var last3 = String(hash).substring(String(hash).length - 3);
      g = parseInt(last3.charAt(2) + last3.charAt(0) + last3.charAt(1), 16);
    }
    if (isNaN(g)) g = 0;
    return (typeof gg.m === "function" ? gg.m(g) : 0) + 1;
  }

  source = {
    id: "hitomi",
    displayName: "Hitomi",
    apiLevel: 1,
    origin: ORIGIN,
    catalogs: [
      { id: "LATEST", label: "최신", paginated: true },
      { id: "POPULAR", label: "인기", paginated: true },
      { id: "TODAY", label: "오늘", paginated: true },
      { id: "MONTH", label: "월간", paginated: true },
      { id: "YEAR", label: "연간", paginated: true },
      { id: "DOUJINSHI", label: "동인지", paginated: true },
      { id: "MANGA", label: "망가", paginated: true },
      { id: "ARTISTCG", label: "아티스트 CG", paginated: true },
      { id: "GAMECG", label: "게임 CG", paginated: true },
      { id: "IMAGESET", label: "이미지셋", paginated: true },
    ],
    searchPlaceholder: "작가 검색 (artist: 이름)",
    notificationMode: "PER_FAVORITE",
    episodePageSize: PAGE_SIZE,
    emptyListingOk: true,
    emptyEpisodesOk: true,
    defaultLanguage: DEFAULT_LANGUAGE,
    progressDisplay: "READ_COUNT",
    userAgent: null,
    referer: REFERER,
    extraHeaders: {
      Accept: "*/*",
      "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8",
    },

    loadListing: function (catalogId, page) {
      var p = Math.max(1, page | 0);
      var result = fetchNozomiPage(listingNozomi(catalogId), p);
      var ids = parseIds(result.body);
      return {
        items: loadGalleryCards(ids),
        currentPage: p,
        lastPage: lastPage(p, ids, result.totalLength()),
      };
    },

    search: function (query) {
      var tokens = parseSearch(query && query.text ? query.text : "");
      var qLang = query && query.language ? query.language : null;
      var qType = query && query.type ? query.type : null;
      if (!tokens.length && !qLang && !qType) return [];
      var lang = currentLanguage();
      if (qLang) lang = qLang;
      var i;
      for (i = 0; i < tokens.length; i++) {
        if (tokens[i].ns === "language") {
          lang = normalizeSlug(tokens[i].value);
          break;
        }
      }
      var filters = [];
      for (i = 0; i < tokens.length; i++) {
        if (tokens[i].ns !== "language") filters.push(tokens[i]);
      }
      if (qType) {
        var hasType = false;
        for (i = 0; i < filters.length; i++) {
          if (filters[i].ns === "type") {
            hasType = true;
            break;
          }
        }
        if (!hasType) filters.push({ ns: "type", value: qType });
      }
      if (filters.length === 1 && SERIES_NAMESPACES[filters[0].ns]) {
        return [nsSeriesItem(filters[0].ns, filters[0].value, lang)];
      }
      var urls = [];
      if (!filters.length) {
        urls.push(indexNozomi(lang));
      } else {
        for (i = 0; i < filters.length; i++) urls.push(nozomiUrlForToken(filters[i], lang));
      }
      return loadGalleryCards(collectSearchIds(urls));
    },

    suggest: function (query) {
      var url = suggestUrl(query && query.text ? query.text : "");
      if (!url) return [];
      var result = host.fetch({ url: url });
      if (result.code < 200 || result.code > 299) return [];
      return parseSuggest(host.utf8(result.body));
    },

    loadEpisodes: function (item, page) {
      var p = Math.max(1, page | 0);
      var nsItem = nsSlugOf(item.id);
      if (nsItem) {
        var result = fetchNozomiPage(nsNozomi(nsItem.ns, nsItem.slug, currentLanguage()), p);
        var ids = parseIds(result.body);
        var infos = loadGalleryInfos(ids);
        var episodes = [];
        var seen = {};
        for (var i = 0; i < infos.length; i++) {
          var ep = toEpisodeItem(infos[i]);
          if (seen[ep.wrId]) continue;
          seen[ep.wrId] = true;
          episodes.push(ep);
        }
        return {
          items: episodes,
          currentPage: p,
          lastPage: lastPage(p, ids, result.totalLength()),
        };
      }
      var galleryId = galleryIdOf(item);
      if (!galleryId) return { items: [], currentPage: p, lastPage: 1 };
      var info = fetchGalleryInfo(galleryId);
      if (!info) return { items: [], currentPage: p, lastPage: 1 };
      if (lower(info.type) === "anime") return { items: [], currentPage: p, lastPage: 1 };
      return { items: [toEpisodeItem(info)], currentPage: p, lastPage: 1 };
    },

    resolveImages: function (episode, item) {
      var info = fetchGalleryInfo(episode.wrId);
      if (!info) throw new Error("만화 이미지를 불러오지 못했습니다.");
      var gg = loadGg();
      if (!info.files.length) throw new Error("만화 이미지를 불러오지 못했습니다.");
      var urls = [];
      for (var i = 0; i < info.files.length; i++) {
        var file = info.files[i];
        var ext = file.hasWebp ? "webp" : file.hasAvif ? "avif" : "webp";
        urls.push(imageUrl(file.hash, gg.b, subdomainIndex(gg, file.hash), ext, gg));
      }
      return urls;
    },

    imageFallbacks: function (url) {
      var out = [];
      var seen = {};
      function add(u) {
        if (!u || seen[u]) return;
        seen[u] = true;
        out.push(u);
      }
      add(altImageUrl(url));
      var thumbs = thumbFallbacks(url);
      for (var i = 0; i < thumbs.length; i++) add(thumbs[i]);
      return out;
    },

    imageReferer: function (url) {
      return REFERER;
    },

    coverUrl: function (toonId) {
      return null;
    },

    ownsHost: function (hostName) {
      var h = lower(hostName);
      return h === "hitomi.la" ||
        endsWith(h, ".hitomi.la") ||
        h === CDN_HOST ||
        endsWith(h, "." + CDN_HOST);
    },

    useProxy: function (url) {
      return false;
    },

    resolveParent: function (item, choice, entryEpisodeId) {
      var entry = entryEpisodeId != null && entryEpisodeId !== "" ? entryEpisodeId : item.entryEpisodeId;
      return {
        id: ARTIST_PREFIX + choice.slug,
        title: choice.displayName,
        thumbUrl: item.thumbUrl,
        href: nsPage("artist", choice.slug),
        genre: item.genre,
        updatedAt: item.updatedAt,
        entryEpisodeId: entry,
      };
    },

    supportsChapterNotifications: function (item) {
      return startsWith(item.id, ARTIST_PREFIX);
    },

    notificationCandidates: function (favorites) {
      var out = [];
      if (!favorites || favorites.length == null) return out;
      for (var i = 0; i < favorites.length; i++) {
        if (startsWith(favorites[i].id, ARTIST_PREFIX)) out.push(favorites[i]);
      }
      return out;
    },

    applyConfig: function (config) {
      if (!config || config.language == null || config.language === undefined) return;
      var next = trim(config.language);
      language = next || DEFAULT_LANGUAGE;
    },
  };
})();
