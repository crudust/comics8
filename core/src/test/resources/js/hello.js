var source = {
  id: "hello",
  displayName: "Hello",
  apiLevel: 1,
  origin: "https://hello.test",
  catalogs: [
    { id: "LATEST", label: "최신", paginated: true }
  ],
  searchPlaceholder: "제목 검색",
  notificationMode: "NONE",
  episodePageSize: 100,
  emptyListingOk: true,
  emptyEpisodesOk: true,
  defaultLanguage: null,
  progressDisplay: "LAST_READ_ORDER",
  userAgent: null,
  referer: null,
  extraHeaders: { "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8" },

  loadListing: function (catalogId, page) {
    var html = host.fetchText({ url: this.origin + "/list?page=" + page });
    var doc = host.parseHtml(html, this.origin);
    var nodes = doc.select("a.item");
    var items = [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      items.push({
        id: el.attr("data-id"),
        title: el.text(),
        thumbUrl: el.absUrl("data-thumb"),
        href: el.absUrl("href"),
        sourceId: "stale"
      });
    }
    return { items: items, currentPage: page, lastPage: 1 };
  },

  search: function (query) {
    var text = host.fetchText({
      url: this.origin + "/search?q=" + encodeURIComponent(query.text)
    });
    var data = host.json(text);
    var items = [];
    for (var i = 0; i < data.length; i++) {
      items.push({
        id: String(data[i].id),
        title: data[i].title,
        thumbUrl: data[i].thumbUrl || "",
        href: data[i].href || ""
      });
    }
    return items;
  },

  suggest: function (query) {
    return [];
  },

  loadEpisodes: function (item, page) {
    return {
      items: [{ wrId: item.id, title: item.title, href: item.href }],
      currentPage: 1,
      lastPage: 1
    };
  },

  resolveImages: function (episode, item) {
    return [this.origin + "/img/1.jpg"];
  },

  imageFallbacks: function (url) {
    return [];
  },

  imageReferer: function (url) {
    return this.origin;
  },

  coverUrl: function (toonId) {
    return null;
  },

  ownsHost: function (hostName) {
    return hostName === "hello.test";
  },

  useProxy: function (url) {
    return true;
  },

  resolveParent: function (item, choice, entryEpisodeId) {
    return null;
  },

  applyConfig: function (config) {}
};
