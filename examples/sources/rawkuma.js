var ORIGIN = "https://rawkuma.net";

function decodeEntities(str) {
  if (!str) return "";
  return String(str)
    .replace(/&#8217;/g, "'")
    .replace(/&#8216;/g, "'")
    .replace(/&#8220;/g, '"')
    .replace(/&#8221;/g, '"')
    .replace(/&#8211;/g, "-")
    .replace(/&#8212;/g, "--")
    .replace(/&#038;/g, "&")
    .replace(/&#039;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"');
}

function parseCard(container, baseUrl) {
  var a = container.selectFirst("a[href*='/manga/']");
  if (!a) return null;
  var href = host.absUrl(a.attr("href"), baseUrl || ORIGIN);
  var slugMatch = href.match(/\/manga\/([^\/]+)/);
  var id = slugMatch ? slugMatch[1] : "";
  if (!id || id === "feed") return null;

  var img = container.selectFirst("img");
  var thumbUrl = img ? (img.attr("src") || img.attr("data-src") || "") : "";
  if (!thumbUrl) {
    var bgEl = container.selectFirst("[style*='background-image']");
    if (bgEl) thumbUrl = bgEl.bgUrl() || "";
  }
  // If there is no valid thumbnail, do NOT treat this as a valid card (skip it)
  if (!thumbUrl) return null;
  thumbUrl = host.absUrl(thumbUrl, baseUrl || ORIGIN);

  var title = (img ? img.attr("alt") : "") || container.textOf("h4") || container.textOf("h1") || container.textOf("h2") || container.textOf("h3") || a.attr("title") || container.textOf(".font-semibold");
  title = decodeEntities(title.trim());

  var genre = container.textOf(".font-normal.text-xs") || container.textOf(".numscore");
  genre = genre.replace(/\s+/g, " ").trim();

  var timeEl = container.selectFirst("time");
  var updatedAt = timeEl ? (timeEl.text() || timeEl.attr("datetime")) : null;
  if (!updatedAt) {
    var epEl = container.selectFirst(".text-xs, .epx, .chapter, span.float-end");
    if (epEl) updatedAt = epEl.text().trim();
  }

  return {
    id: id,
    title: title || id,
    thumbUrl: thumbUrl,
    href: href,
    genre: genre,
    updatedAt: updatedAt
  };
}

var source = {
  id: "rawkuma",
  displayName: "Rawkuma",
  origin: ORIGIN,
  catalogs: [
    { id: "LATEST", label: "Latest", paginated: false },
    { id: "ALL", label: "All Manga", paginated: true }
  ],
  searchPlaceholder: "Search manga...",
  episodePageSize: 500,
  extraHeaders: {
    "Referer": ORIGIN + "/"
  },

  loadListing: function (catalogId, page) {
    var cat = String(catalogId || "LATEST").toUpperCase();
    var p = page < 1 ? 1 : page;

    if (cat === "LATEST") {
      var homeHtml = host.fetchText({ url: ORIGIN + "/" });
      var homeDoc = host.parseHtml(homeHtml, ORIGIN);
      var items = [];
      var seen = {};

      // 1. Swiper slide banners (with cover image)
      var slides = homeDoc.select(".swiper-slide");
      for (var s = 0; s < slides.length; s++) {
        var slide = slides[s];
        var sImg = slide.selectFirst("img");
        var sLink = slide.selectFirst("a[href*='/manga/']");
        if (sImg && sLink) {
          var sHref = host.absUrl(sLink.attr("href"), ORIGIN);
          var sSlug = host.match(sHref, "/manga/([^/]+)");
          if (sSlug && sSlug !== "feed" && !seen[sSlug]) {
            seen[sSlug] = true;
            var sThumb = sImg.attr("src") || sImg.attr("data-src") || "";
            var sTitle = sImg.attr("alt") || slide.textOf(".line-clamp-2") || slide.textOf("h1") || sSlug;
            items.push({
              id: sSlug,
              title: decodeEntities(sTitle.trim()),
              thumbUrl: host.absUrl(sThumb, ORIGIN),
              href: sHref,
              genre: "",
              updatedAt: null
            });
          }
        }
      }

      // 2. All content cards on home page (Popular Today + Latest Updates + Projects)
      var allCards = homeDoc.select("div.col-span-1, div.group-data-\\[direction\\=horizontal\\]\\:hidden, div.overflow-hidden.relative.flex.flex-col.min-w-0, div.px-2\\.5.py-3\\.75");
      for (var i = 0; i < allCards.length; i++) {
        var card = allCards[i];
        var item = parseCard(card, ORIGIN);
        if (item && !seen[item.id]) {
          seen[item.id] = true;
          items.push(item);
        }
      }

      // 3. Fallback: all manga links that contain an img tag
      var anchors = homeDoc.select("a[href*='/manga/']");
      for (var j = 0; j < anchors.length; j++) {
        var a = anchors[j];
        var href = a.absUrl("href");
        var slug = host.match(href, "/manga/([^/]+)");
        if (!slug || slug === "feed" || seen[slug]) continue;
        var img = a.selectFirst("img");
        if (!img) continue; // Skip links without images!
        var thumbUrl = img.absUrl("src") || img.absUrl("data-src") || "";
        if (!thumbUrl) continue;
        seen[slug] = true;
        var title = img.attr("alt") || a.text().trim() || slug;
        items.push({
          id: slug,
          title: decodeEntities(title.trim()),
          thumbUrl: thumbUrl,
          href: href,
          genre: "",
          updatedAt: null
        });
      }

      return {
        items: items,
        pageInfo: { currentPage: 1, lastPage: 1 }
      };
    }

    // ALL (Archive /manga/page/{p}/)
    var url = (p === 1 ? ORIGIN + "/manga/" : ORIGIN + "/manga/page/" + p + "/");
    var html = host.fetchText({ url: url });
    var doc = host.parseHtml(html, ORIGIN);
    var cards = doc.select("div.group-data-\\[direction\\=horizontal\\]\\:hidden, div.overflow-hidden.relative.flex.flex-col.min-w-0");
    var items = [];
    var seen = {};

    for (var k = 0; k < cards.length; k++) {
      var aCard = cards[k];
      var it = parseCard(aCard, ORIGIN);
      if (it && !seen[it.id]) {
        seen[it.id] = true;
        items.push(it);
      }
    }

    var pageInfo = host.parsePageInfo(doc);
    if (pageInfo.lastPage <= 1) {
      var pagLinks = doc.select("a[href*='/manga/page/']");
      var maxP = p;
      for (var l = 0; l < pagLinks.length; l++) {
        var pHref = pagLinks[l].attr("href");
        var pNum = parseInt(host.match(pHref, "/manga/page/(\\d+)", 1), 10);
        if (!isNaN(pNum) && pNum > maxP) maxP = pNum;
      }
      pageInfo = { currentPage: p, lastPage: maxP };
    }

    return {
      items: items,
      pageInfo: pageInfo
    };
  },

  search: function (query) {
    var q = (query && query.text || "").trim();
    if (!q) return [];

    var items = [];
    var seen = {};

    try {
      var restUrl = ORIGIN + "/wp-json/wp/v2/manga?search=" + encodeURIComponent(q) + "&_embed=1";
      var jsonText = host.fetchText({
        url: restUrl,
        headers: { "Referer": ORIGIN + "/" }
      });
      var list = host.json(jsonText);
      if (list && list.length) {
        for (var i = 0; i < list.length; i++) {
          var row = list[i];
          if (!row) continue;
          var slug = String(row.slug || "").trim();
          if (!slug || slug === "feed" || seen[slug]) continue;
          seen[slug] = true;

          var title = (row.title && row.title.rendered) ? row.title.rendered : slug;
          var link = row.link || (ORIGIN + "/manga/" + slug + "/");

          var thumbUrl = "";
          if (row._embedded && row._embedded["wp:featuredmedia"] && row._embedded["wp:featuredmedia"].length) {
            var media = row._embedded["wp:featuredmedia"][0];
            if (media && media.source_url) {
              thumbUrl = media.source_url;
            }
          }

          var desc = "";
          if (row.content && row.content.rendered) {
            desc = row.content.rendered.replace(/<[^>]+>/g, "").trim();
            if (desc.length > 80) desc = desc.substring(0, 80) + "...";
          }

          items.push({
            id: slug,
            title: decodeEntities(title),
            thumbUrl: thumbUrl ? host.absUrl(thumbUrl, ORIGIN) : "",
            href: host.absUrl(link, ORIGIN),
            genre: decodeEntities(desc),
            updatedAt: null
          });
        }
      }
    } catch (e) {}

    return items;
  },

  loadEpisodes: function (item, page) {
    var html = host.fetchText({ url: item.href });
    var doc = host.parseHtml(html, ORIGIN);
    var rows = doc.select("#chapter-list div[data-chapter-number]");
    var items = [];
    var seen = {};

    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      var a = row.selectFirst("a[href]");
      if (!a) continue;

      var href = a.absUrl("href");
      var chapterNumber = row.attr("data-chapter-number").trim();
      var wrId = host.match(href, "chapter-([0-9a-zA-Z._-]+)") || chapterNumber;
      if (!wrId || seen[wrId]) continue;
      seen[wrId] = true;

      var title = row.textOf(".font-medium") || row.textOf("span") || ("Chapter " + chapterNumber);
      title = decodeEntities(title.trim());

      var timeEl = row.selectFirst("time");
      var rawDate = timeEl ? (timeEl.attr("datetime") || timeEl.text()) : null;
      var date = rawDate ? host.match(rawDate, "(\\d{4}-\\d{2}-\\d{2})", 1) : null;
      if (!date && rawDate) {
        date = rawDate.trim();
      }

      var img = row.selectFirst("img");
      var thumbUrl = img ? img.absUrl("src") : (item.thumbUrl || null);

      items.push({
        wrId: wrId,
        title: title,
        date: date,
        thumbUrl: thumbUrl,
        href: href
      });
    }

    return {
      items: items,
      pageInfo: { currentPage: 1, lastPage: 1 }
    };
  },

  resolveImages: function (episode, item) {
    var referer = item && item.href ? item.href : ORIGIN + "/";
    var html = host.fetchText({
      url: episode.href,
      headers: { "Referer": referer }
    });
    var doc = host.parseHtml(html, ORIGIN);
    var imgNodes = doc.select("section[data-image-data] img, section[data-image-data='1'] img");
    var images = [];
    var seen = {};

    for (var i = 0; i < imgNodes.length; i++) {
      var img = imgNodes[i];
      var raw = img.attr("data-src") || img.attr("src") || "";
      raw = raw.trim();
      if (!raw) continue;
      var fullUrl = host.absUrl(raw, ORIGIN);
      if (!seen[fullUrl]) {
        seen[fullUrl] = true;
        images.push(fullUrl);
      }
    }

    if (images.length === 0) {
      images = host.extractImages(html, ORIGIN);
    }

    return images;
  },

  ownsHost: function (h) {
    var hostName = String(h || "").toLowerCase();
    return hostName === "rawkuma.net" ||
           hostName.endsWith(".rawkuma.net") ||
           hostName === "kyut.dev" ||
           hostName.endsWith(".kyut.dev");
  },

  imageReferer: function (url) {
    return ORIGIN + "/";
  }
};
