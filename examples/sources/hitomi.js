var source;

(function () {
  var ORIGIN = "https://hitomi.la";
  var REFERER = "https://hitomi.la/";
  var LTN = "https://ltn.gold-usergeneratedcontent.net";
  var CDN_HOST = "gold-usergeneratedcontent.net";
  var TAGINDEX = "https://tagindex.hitomi.la";
  var DEFAULT_LANG = "korean";
  var PAGE_SIZE = 25;
  var GALLERY_CONCURRENCY = 6;
  var ARTIST_PREFIX = "artist:";
  var GALLERY_PREFIX = "gallery:";
  var SERIES_NS = { artist: true, series: true, character: true, group: true };
  var NAMESPACES = { language: true, type: true, female: true, male: true, tag: true, artist: true, series: true, character: true, group: true };
  var EXCLUDED_IMAGE_HASHES = {
    "d48185b927798950699899130d070961b6d0336fb0fa0d63e237c4c493cd58d7": true,
    "87533c35d6658dcc792a2bbd0491e9dd3439f5bf1521aabe5d3089cab099be95": true,
    "d0a9889842c04f64ba3e8a961b234693a48b1a9229665ce6c11f47fad0a35047": true,
    "d97e692da6eea7545799610ea38a0805a28f1d49c007544e12d51acc2676761c": true,
    "86e1619d413f090cb396e595706da0ee31a1e349c782e0edc6fa7429e0bb42f1": true,
    "b27e783e21b995917a121c738683431431e05f95e9fc2abf3bb5c304b087a4e1": true,
    "b5615f4c1663de9fc414e299dff2b5c3627440a4df2f6540a78fb22b51fd2e2d": true,
    "3b9e0af962469ef2550d59de199c662ba6d291f986ecd0812715c2c7664903a6": true,
    "f3618fd40ff4329e4c7304eaa2d036de37d89484e3d55dbd9479cd4f176f61e3": true,
    "a263c82ea591691498a65686a58f5307660cdb3827e481e9b4fc6308cf694111": true
  };

  var language = DEFAULT_LANG;

  function currentLang() {
    return host.language || language;
  }

  function encodePath(v) {
    return encodeURIComponent(v).replace(/!/g, "%21").replace(/'/g, "%27").replace(/\(/g, "%28").replace(/\)/g, "%29");
  }

  function indexNozomi(lang) {
    var l = (lang || DEFAULT_LANG).toLowerCase();
    return LTN + "/index-" + (l === "all" ? "all" : l) + ".nozomi";
  }

  function typeNozomi(type, lang) {
    return LTN + "/" + type + "-" + (lang || DEFAULT_LANG) + ".nozomi";
  }

  function popularNozomi(period, lang) {
    return LTN + "/popular/" + period + "-" + (lang || DEFAULT_LANG) + ".nozomi";
  }

  function nsNozomi(ns, slug, lang) {
    return LTN + "/" + ns + "/" + encodePath(slug.replace(/_/g, " ")) + "-" + (lang || DEFAULT_LANG) + ".nozomi";
  }

  function galleryJs(id) {
    return LTN + "/galleries/" + id + ".js";
  }

  function thumbUrl(hash) {
    if (!hash || hash.length < 3) return "";
    var s = hash.charAt(hash.length - 1) + "/" + hash.charAt(hash.length - 3) + hash.charAt(hash.length - 2);
    return "https://tn." + CDN_HOST + "/webpsmalltn/" + s + "/" + hash + ".webp";
  }

  function imageUrl(hash, b, n, ext, gg) {
    var hostPrefix = (ext || "").toLowerCase() === "avif" ? "a" : "w";
    var last3 = String(hash).substring(String(hash).length - 3);
    var s = (gg && typeof gg.s === "function") ? String(gg.s(hash)) : String(parseInt(last3.charAt(2) + last3.charAt(0) + last3.charAt(1), 16) || 0);
    var prefix = !b ? "" : (b.charAt(b.length - 1) === "/" ? b : b + "/");
    return "https://" + hostPrefix + n + "." + CDN_HOST + "/" + prefix + s + "/" + hash + "." + ext;
  }

  function parseGalleryInfo(text) {
    var obj;
    try {
      obj = host.evalSiteJs("galleryinfo", text);
    } catch (e) {
      return null;
    }
    if (!obj || !obj.id) return null;
    var id = String(obj.id).trim();
    if (!id || id === "null") return null;

    var artists = [];
    var rawArtists = obj.artists || [];
    var seenArt = {};
    for (var i = 0; i < rawArtists.length; i++) {
      var a = rawArtists[i];
      if (!a) continue;
      var name = typeof a === "object" ? (a.artist || a.name || "") : String(a);
      name = name.trim();
      if (!name) continue;
      var slug = host.slug(name);
      if (seenArt[slug]) continue;
      seenArt[slug] = true;
      artists.push({ slug: slug, displayName: name });
    }

    var files = [];
    var rawFiles = obj.files || [];
    for (var j = 0; j < rawFiles.length; j++) {
      var f = rawFiles[j];
      if (!f || !f.hash) continue;
      var hashStr = String(f.hash).trim();
      if (EXCLUDED_IMAGE_HASHES[hashStr]) continue;
      files.push({
        hash: hashStr,
        hasWebp: f.haswebp == null ? true : f.haswebp != 0,
        hasAvif: f.hasavif == null ? false : f.hasavif != 0
      });
    }

    var updatedAt = null;
    if (obj.date) {
      var m = /(\d{4})-(\d{2})-(\d{2})/.exec(String(obj.date));
      if (m) updatedAt = m[1].substring(2) + "." + m[2] + "." + m[3];
    }

    return {
      id: id,
      title: (obj.title || id).trim(),
      type: (obj.type || "").trim(),
      updatedAt: updatedAt,
      artists: artists,
      files: files
    };
  }

  function fetchGalleryInfo(id) {
    var key = "hitomi:g:" + id;
    var cached = host.cacheGet(key);
    if (cached) return cached;
    var res = host.fetch({ url: galleryJs(id) });
    if (res.code < 200 || res.code > 299) return null;
    var parsed = parseGalleryInfo(host.utf8(res.body));
    if (parsed) host.cachePut(key, parsed, 600000);
    return parsed;
  }

  function loadGalleryInfos(ids) {
    if (!ids || !ids.length) return [];
    var slots = [];
    var missing = [];
    var missingIdx = [];
    for (var i = 0; i < ids.length; i++) {
      var sid = String(ids[i]);
      var cached = host.cacheGet("hitomi:g:" + sid);
      if (cached) {
        slots[i] = cached;
      } else {
        missing.push(sid);
        missingIdx.push(i);
      }
    }
    if (missing.length) {
      var specs = missing.map(function (mid) { return { url: galleryJs(mid) }; });
      var results = host.fetchAll(specs, GALLERY_CONCURRENCY);
      for (var j = 0; j < results.length; j++) {
        var r = results[j];
        if (r.code < 200 || r.code > 299) continue;
        var info = parseGalleryInfo(host.utf8(r.body));
        if (!info) continue;
        host.cachePut("hitomi:g:" + info.id, info, 600000);
        slots[missingIdx[j]] = info;
      }
    }
    var out = [];
    var seen = {};
    for (var k = 0; k < slots.length; k++) {
      var g = slots[k];
      if (!g || (g.type || "").toLowerCase() === "anime" || seen[g.id]) continue;
      seen[g.id] = true;
      out.push(g);
    }
    return out;
  }

  function toListingItem(info) {
    if ((info.type || "").toLowerCase() === "anime") return null;
    var toonId = info.artists.length === 1 ? (ARTIST_PREFIX + info.artists[0].slug) : (GALLERY_PREFIX + info.id);
    var title = info.artists.length === 1 ? info.artists[0].displayName : info.title;
    var choices = info.artists.length >= 2 ? info.artists : [];
    var first = info.files.length ? info.files[0] : null;
    return {
      id: toonId,
      title: title,
      thumbUrl: first ? thumbUrl(first.hash) : "",
      href: ORIGIN + "/galleries/" + info.id + ".html",
      genre: info.type,
      updatedAt: info.updatedAt,
      entryEpisodeId: info.id,
      artistChoices: choices
    };
  }

  function loadGalleryCards(ids) {
    var infos = loadGalleryInfos(ids);
    var items = [];
    var seen = {};
    for (var i = 0; i < infos.length; i++) {
      var item = toListingItem(infos[i]);
      if (!item) continue;
      var key = item.entryEpisodeId ? ("hitomi:g:" + item.entryEpisodeId) : ("hitomi:" + item.id);
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
      href: ORIGIN + "/reader/" + info.id + ".html",
      artistChoices: info.artists.length >= 2 ? info.artists : []
    };
  }

  function listingNozomi(catalogId) {
    var lang = currentLang();
    var cat = String(catalogId || "").toUpperCase();
    if (cat === "LATEST" || cat === "INDEX") return indexNozomi(lang);
    if (cat === "POPULAR") return popularNozomi("week", lang);
    if (cat === "TODAY") return popularNozomi("today", lang);
    if (cat === "MONTH") return popularNozomi("month", lang);
    if (cat === "YEAR") return popularNozomi("year", lang);
    if (cat === "DOUJINSHI" || cat === "MANGA" || cat === "ARTISTCG" || cat === "GAMECG" || cat === "IMAGESET") {
      return typeNozomi(cat.toLowerCase(), lang);
    }
    throw new Error("Unknown catalog: " + catalogId);
  }

  function parseSearch(text) {
    var normalized = (text || "").trim().replace(
      /(language|type|female|male|tag|artist|series|character|group):\s+/gi,
      function (_, ns) { return ns.toLowerCase() + ":"; }
    );
    if (!normalized) return [];
    var parts = normalized.split(/\s+/);
    var tokens = [];
    var bare = [];
    function flushBare() {
      if (!bare.length) return;
      tokens.push({ ns: "artist", value: host.slug(bare.join(" ")) });
      bare = [];
    }
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (!p || p.charAt(0) === "-") continue;
      if (p.indexOf("tag:female:") === 0) p = p.substring(4);
      if (p.indexOf("tag:male:") === 0) p = p.substring(4);
      var idx = p.indexOf(":");
      if (idx > 0) {
        var ns = p.substring(0, idx).toLowerCase();
        var val = host.slug(p.substring(idx + 1));
        if (NAMESPACES[ns] && val) {
          flushBare();
          tokens.push({ ns: ns, value: val });
          continue;
        }
      }
      bare.push(p);
    }
    flushBare();
    return tokens;
  }

  function nozomiUrlForToken(token, lang) {
    var l = (lang || DEFAULT_LANG).toLowerCase();
    var slug = host.slug(token.value);
    if (token.ns === "language") return indexNozomi(slug);
    if (token.ns === "type") return typeNozomi(slug, l);
    if (token.ns === "female" || token.ns === "male") {
      return LTN + "/tag/" + encodePath(token.ns + ":" + slug.replace(/_/g, " ")) + "-" + l + ".nozomi";
    }
    if (token.ns === "tag") {
      var femaleUrl = LTN + "/tag/" + encodePath("female:" + slug.replace(/_/g, " ")) + "-" + l + ".nozomi";
      if (host.isAccessible(femaleUrl)) return femaleUrl;
      var maleUrl = LTN + "/tag/" + encodePath("male:" + slug.replace(/_/g, " ")) + "-" + l + ".nozomi";
      if (host.isAccessible(maleUrl)) return maleUrl;
    }
    return nsNozomi(token.ns, slug, l);
  }

  source = {
    id: "hitomi",
    displayName: "Hitomi",
    origin: ORIGIN,
    catalogs: [
      { id: "LATEST", label: "Latest", paginated: true },
      { id: "POPULAR", label: "Popular", paginated: true },
      { id: "TODAY", label: "Today", paginated: true },
      { id: "MONTH", label: "Month", paginated: true },
      { id: "YEAR", label: "Year", paginated: true },
      { id: "DOUJINSHI", label: "Doujinshi", paginated: true },
      { id: "MANGA", label: "Manga", paginated: true },
      { id: "ARTISTCG", label: "Artist CG", paginated: true },
      { id: "GAMECG", label: "Game CG", paginated: true },
      { id: "IMAGESET", label: "Image Set", paginated: true }
    ],
    searchPlaceholder: "Search artist:name...",
    notificationMode: "PER_FAVORITE",
    episodePageSize: PAGE_SIZE,
    emptyListingOk: true,
    emptyEpisodesOk: true,
    defaultLanguage: DEFAULT_LANG,
    progressDisplay: "READ_COUNT",
    referer: REFERER,

    loadListing: function (catalogId, page) {
      var res = host.fetchInt32Index(listingNozomi(catalogId), page, PAGE_SIZE);
      return {
        items: loadGalleryCards(res.ids),
        currentPage: res.currentPage,
        lastPage: res.lastPage
      };
    },

    search: function (query) {
      var tokens = parseSearch(query && query.text ? query.text : "");
      var lang = (query && query.language) || currentLang();
      var qType = query && query.type;
      for (var i = 0; i < tokens.length; i++) {
        if (tokens[i].ns === "language") {
          lang = host.slug(tokens[i].value);
          break;
        }
      }
      var filters = tokens.filter(function (t) { return t.ns !== "language"; });
      if (qType && !filters.some(function (t) { return t.ns === "type"; })) {
        filters.push({ ns: "type", value: qType });
      }
      if (filters.length === 1 && SERIES_NS[filters[0].ns]) {
        var token = filters[0];
        var artistNozomi = nsNozomi(token.ns, token.value, lang);
        if (!host.isAccessible(artistNozomi)) return [];
        var ids = host.fetchInt32Index(artistNozomi, 1, 1).ids;
        var cover = ids.length ? fetchGalleryInfo(String(ids[0])) : null;
        var first = cover && cover.files.length ? cover.files[0] : null;
        return [{
          id: token.ns + ":" + token.value,
          title: token.value.replace(/_/g, " "),
          thumbUrl: first ? thumbUrl(first.hash) : "",
          href: ORIGIN + "/" + token.ns + "/" + encodePath(token.value.replace(/_/g, " ")) + "-all.html",
          genre: cover && cover.type ? cover.type : token.ns,
          updatedAt: cover ? cover.updatedAt : null,
          entryEpisodeId: cover ? cover.id : null
        }];
      }
      var urls = filters.length ? filters.map(function (f) { return nozomiUrlForToken(f, lang); }) : [indexNozomi(lang)];
      for (var u = 0; u < urls.length; u++) {
        if (!host.isAccessible(urls[u])) return [];
      }
      var matchedIds = host.intersectIndexUrls(urls, PAGE_SIZE);
      return loadGalleryCards(matchedIds);
    },

    suggest: function (query) {
      var text = (query && query.text || "").trim();
      if (!text) return [];
      var parts = text.split(/\s+/);
      var last = parts[parts.length - 1] || "";
      if (last.charAt(0) === "-") last = last.substring(1);
      if (last.indexOf("tag:female:") === 0) last = last.substring(4);
      if (last.indexOf("tag:male:") === 0) last = last.substring(4);
      var ns = "artist";
      var term = last;
      var idx = last.indexOf(":");
      if (idx > 0) {
        var pre = last.substring(0, idx).toLowerCase();
        if (NAMESPACES[pre]) {
          ns = pre;
          term = last.substring(idx + 1);
        }
      }
      term = term.toLowerCase().replace(/_/g, " ");
      if (!term) return [];
      var path = term.split("").map(function (ch) {
        return ch === " " ? "_" : ch === "/" ? "slash" : ch === "." ? "dot" : ch;
      }).join("/");
      var root = host.fetchJson({ url: TAGINDEX + "/" + ns + "/" + path + ".json" });
      if (!root || !root.length) return [];
      var out = [];
      for (var i = 0; i < root.length && out.length < 10; i++) {
        var row = root[i];
        if (!row || !row[0]) continue;
        out.push({ ns: row[2] || "artist", tag: String(row[0]).trim(), count: parseInt(row[1], 10) || 0 });
      }
      return out;
    },

    loadEpisodes: function (item, page) {
      var p = Math.max(1, page | 0);
      var id = String(item.id || "");
      var idx = id.indexOf(":");
      if (idx > 0 && SERIES_NS[id.substring(0, idx)]) {
        var ns = id.substring(0, idx);
        var slug = id.substring(idx + 1);
        var res = host.fetchInt32Index(nsNozomi(ns, slug, currentLang()), p, PAGE_SIZE);
        var infos = loadGalleryInfos(res.ids);
        return {
          items: infos.map(toEpisodeItem),
          currentPage: res.currentPage,
          lastPage: res.lastPage
        };
      }
      var galleryId = item.entryEpisodeId || (id.indexOf(GALLERY_PREFIX) === 0 ? id.substring(GALLERY_PREFIX.length) : id);
      var info = fetchGalleryInfo(galleryId);
      if (!info || (info.type || "").toLowerCase() === "anime") return { items: [], currentPage: p, lastPage: 1 };
      return { items: [toEpisodeItem(info)], currentPage: p, lastPage: 1 };
    },

    resolveImages: function (episode) {
      var info = fetchGalleryInfo(episode.wrId);
      if (!info || !info.files.length) return [];
      var res = host.fetch({ url: LTN + "/gg.js" });
      if (res.code < 200 || res.code > 299) return [];
      var gg = host.evalSiteJs("gg", host.utf8(res.body));
      if (!gg || !gg.b) return [];

      var urls = [];
      for (var i = 0; i < info.files.length; i++) {
        var f = info.files[i];
        var ext = f.hasWebp ? "webp" : f.hasAvif ? "avif" : "webp";
        var last3 = String(f.hash).substring(String(f.hash).length - 3);
        var g = typeof gg.s === "function" ? parseInt(gg.s(f.hash), 10) : parseInt(last3.charAt(2) + last3.charAt(0) + last3.charAt(1), 16);
        if (isNaN(g)) g = 0;
        var n = (typeof gg.m === "function" ? gg.m(g) : 0) + 1;
        urls.push(imageUrl(f.hash, gg.b, n, ext, gg));
      }
      return urls;
    },

    imageFallbacks: function (url) {
      if (!url) return [];
      var out = [];
      if (url.indexOf(CDN_HOST) >= 0) {
        if (url.indexOf("://w") >= 0 && url.indexOf(".webp") >= 0) {
          out.push(url.replace(/:\/\/w(\d+)\./, "://a$1.").replace(/\.webp$/, ".avif"));
        } else if (url.indexOf("://a") >= 0 && url.indexOf(".avif") >= 0) {
          out.push(url.replace(/:\/\/a(\d+)\./, "://w$1.").replace(/\.avif$/, ".webp"));
        }
      }
      return out;
    },

    ownsHost: function (h) {
      var hostName = (h || "").toLowerCase();
      return hostName === "hitomi.la" || hostName.endsWith(".hitomi.la") || hostName === CDN_HOST || hostName.endsWith("." + CDN_HOST);
    },

    useProxy: function () {
      return false;
    },

    resolveParent: function (item, choice, entryEpisodeId) {
      return {
        id: ARTIST_PREFIX + choice.slug,
        title: choice.displayName,
        thumbUrl: item.thumbUrl,
        href: ORIGIN + "/artist/" + encodePath(choice.slug.replace(/_/g, " ")) + "-all.html",
        genre: item.genre,
        updatedAt: item.updatedAt,
        entryEpisodeId: entryEpisodeId || item.entryEpisodeId
      };
    },

    applyConfig: function (config) {
      if (config && config.language) language = config.language.trim() || DEFAULT_LANG;
    }
  };
})();
