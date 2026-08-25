// Pepper & Carrot (https://www.peppercarrot.com)
// Open-source webcomic by David Revoy, licensed under Creative Commons Attribution 4.0 (CC BY 4.0).
// Minimal & clean sample source plugin for Comics8.

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

  // 1. 카탈로그 목록: "Pepper & Carrot" 대표 작품 카드 반환
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

  // 2. 에피소드 목록: 전체 39화 에피소드 리스트 반환
  loadEpisodes: function (item, page) {
    var lang = host.match(item && item.href, "peppercarrot.com/([^/]+)/", 1) || "en";
    return {
      items: getEpisodes(lang),
      pageInfo: { currentPage: 1, lastPage: 1 }
    };
  },

  // 3. 검색: 에피소드 제목 또는 번호 검색
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

  // 4. 뷰어 이미지: 각 화의 고화질(hi-res) 만화 컷 추출
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

  imageFallbacks: function (url) {
    if (url && url.indexOf("/hi-res/") !== -1) {
      return [url.replace("/hi-res/", "/low-res/")];
    }
    return [];
  },

  ownsHost: function (h) {
    var hostName = String(h || "").toLowerCase();
    return hostName === "peppercarrot.com" || hostName === "www.peppercarrot.com" || hostName === "davidrevoy.com";
  }
};
