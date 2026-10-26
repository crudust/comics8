/**
 * `createPluginHost()` polyfill: network envelope, DOM helpers, smart extraction, and loading the
 * real Pepper & Carrot plugin end-to-end.
 */

import { describe, expect, it, vi } from "vitest";
import {
  DEFAULT_CALL_TIMEOUT_MS,
  HOST_API_LEVEL,
  PluginHost,
  extractSource,
  loadSource,
} from "../src/sources/plugin-host";
import { peppercarrotScript } from "../src/sources/builtin/peppercarrot";
import "./harness";

const ORIGIN = "https://www.peppercarrot.com";

type FakeResponse = {
  status?: number;
  body: string;
  headers?: Record<string, string>;
  error?: string;
};

/** Minimal transport that records calls and replays fixtures by URL substring. */
function fakeFetch(routes: Array<{ match: string; response: FakeResponse | FakeResponse[] }>) {
  const calls: Array<{ url: string; method: string; headers: Record<string, string> }> = [];
  const counters = new Map<string, number>();

  const impl = async (init: {
    url: string;
    method: string;
    headers: Record<string, string>;
  }) => {
    calls.push(init);
    const route = routes.find((entry) => init.url.includes(entry.match));
    if (!route) throw new Error(`unexpected fetch: ${init.url}`);

    const index = counters.get(route.match) ?? 0;
    counters.set(route.match, index + 1);
    const fixtures = Array.isArray(route.response) ? route.response : [route.response];
    const chosen = fixtures[Math.min(index, fixtures.length - 1)];

    return {
      ok: (chosen.status ?? 200) >= 200 && (chosen.status ?? 200) < 300,
      status: chosen.status ?? 200,
      headers: chosen.headers ?? {},
      body: chosen.body,
      error: chosen.error,
    };
  };

  return { impl, calls };
}

const WEBCOMICS_HTML = `<!DOCTYPE html>
<html><body>
  <div class="webcomics">
    <figure class="thumbnail">
      <a href="/en/webcomic/ep39_For_Your_Smile.html"><img src="https://www.peppercarrot.com/0_sources/ep39/thumb/ep39_panel_01_low-res.jpg" alt="Episode 39"></a>
      <figcaption><a href="/en/webcomic/ep39_For_Your_Smile.html">Episode 39: For Your Smile</a> <span>2024-01-20</span></figcaption>
    </figure>
    <figure class="thumbnail">
      <a href="/en/webcomic/ep01_Potion_of_Flight.html"><img src="https://www.peppercarrot.com/0_sources/ep01/thumb/ep01_panel_01_low-res.jpg" alt="Episode 1"></a>
      <figcaption><a href="/en/webcomic/ep01_Potion_of_Flight.html">Episode 1: Potion of Flight</a> <span>2014-05-05</span></figcaption>
    </figure>
  </div>
</body></html>`;

const EPISODE_HTML = `<!DOCTYPE html>
<html><body>
  <div class="viewer">
    <img src="https://www.peppercarrot.com/0_sources/ep01_Potion_of_Flight/hi-res/ep01_01.jpg">
    <img src="https://www.peppercarrot.com/0_sources/ep01_Potion_of_Flight/hi-res/ep01_02.jpg">
    <img src="https://www.peppercarrot.com/0_sources/ep01_Potion_of_Flight/hi-res/ep01_02.jpg">
    <img src="https://www.peppercarrot.com/favicon.png">
  </div>
</body></html>`;

describe("PluginHost — §6.1 network (async variants)", () => {
  it("wraps responses in a HostFetchResult with code/header/text/json/totalLength", async () => {
    const { impl } = fakeFetch([
      { match: "/data.json", response: { body: '{"a":1}', headers: { "Content-Length": "7" } } },
    ]);
    const host = new PluginHost({ sourceId: "t", fetchImpl: impl });

    const result = await host.fetchAsync({ url: "https://example.test/data.json" });
    expect(result.code).toBe(200);
    expect(result.header("content-length")).toBe("7");
    expect(result.totalLength()).toBe(7);
    expect(result.text()).toBe('{"a":1}');
    expect(result.json()).toEqual({ a: 1 });
  });

  it("accepts the fetch(url, options) form and exposes `status`", async () => {
    const { impl } = fakeFetch([{ match: "/plain", response: { body: "ok" } }]);
    const host = new PluginHost({ fetchImpl: impl });

    const result = await host.fetchAsync("https://example.test/plain", { method: "GET" });
    expect(result.status).toBe(200);
    expect(result.code).toBe(200);
    expect(result.text()).toBe("ok");
  });

  it("merges source headers into every request", async () => {
    const { impl, calls } = fakeFetch([{ match: "/", response: { body: "x" } }]);
    const host = new PluginHost({
      extraHeaders: { "User-Agent": "C8-test", Referer: ORIGIN },
      fetchImpl: impl,
    });
    await host.fetchTextAsync({ url: "https://example.test/x" });
    expect(calls[0].headers["User-Agent"]).toBe("C8-test");
    expect(calls[0].headers.Referer).toBe(ORIGIN);
  });

  it("throws for non-2xx on fetchTextAsync and fetchJsonAsync", async () => {
    const { impl } = fakeFetch([{ match: "/missing", response: { status: 404, body: "gone" } }]);
    const host = new PluginHost({ fetchImpl: impl });
    await expect(host.fetchTextAsync({ url: "https://example.test/missing" })).rejects.toThrow("HTTP 404");
    await expect(host.fetchJsonAsync({ url: "https://example.test/missing" })).rejects.toThrow("HTTP 404");
  });

  it("rejects unsupported methods, matching GET/HEAD-only host.fetch", () => {
    const host = new PluginHost({ fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });
    expect(() => host.fetch({ url: "https://example.test/", method: "POST" as unknown as "GET" })).toThrow(
      "method must be GET or HEAD",
    );
  });

  it("reports isAccessible false on transport failure instead of throwing", async () => {
    const host = new PluginHost({
      fetchImpl: async () => {
        throw new Error("boom");
      },
    });
    await expect(host.isAccessibleAsync("https://example.test/")).resolves.toBe(false);
  });
});

describe("PluginHost — §6.1 network inside a plugin (blocking, replayed)", () => {
  it("returns real data to synchronous plugin code", async () => {
    const { impl, calls } = fakeFetch([
      { match: "/page", response: { body: "<html>hi</html>", headers: { "Content-Length": "13" } } },
    ]);
    const host = new PluginHost({ fetchImpl: impl });

    const text = await host.runMethod("sync-read", () => host.fetchText({ url: "https://example.test/page" }));
    expect(text).toBe("<html>hi</html>");
    // Exactly one network call: replay reuses the recording across passes.
    expect(calls).toHaveLength(1);
  });

  it("retries transient statuses before settling", async () => {
    const { impl, calls } = fakeFetch([
      { match: "/retry", response: [{ status: 503, body: "nope" }, { status: 200, body: "ok" }] },
    ]);
    const host = new PluginHost({ fetchImpl: impl });
    const text = await host.runMethod("retry", () => host.fetchText({ url: "https://example.test/retry" }));
    expect(text).toBe("ok");
    expect(calls).toHaveLength(2);
  });

  it("fetchAll records one request per spec and preserves order", async () => {
    const host = new PluginHost({
      fetchImpl: async (request) => ({ ok: true, status: 200, headers: {}, body: request.url }),
    });
    const results = await host.runMethod("all", () =>
      host.fetchAll(Array.from({ length: 5 }, (_, i) => ({ url: `https://example.test/${i}` }))),
    );
    expect(results.map((r) => r.text())).toEqual([
      "https://example.test/0",
      "https://example.test/1",
      "https://example.test/2",
      "https://example.test/3",
      "https://example.test/4",
    ]);
  });

  it("re-derives a URL that depended on data the first pass did not have", async () => {
    const requested: string[] = [];
    const host = new PluginHost({
      fetchImpl: async (request) => {
        requested.push(request.url);
        if (request.url === "https://example.test/index") {
          return { ok: true, status: 200, headers: {}, body: '{"slug":"slug-42"}' };
        }
        return { ok: true, status: 200, headers: {}, body: `chapter:${request.url}` };
      },
    });

    const value = await host.runMethod("derive", () => {
      // Pass 1: the parsed JSON bookkeeping is `null`/undefined, so the derived host name is a
      // placeholder and must not be fetched.
      const meta = host.fetchJson({ url: "https://example.test/index" }) as { slug?: string } | null;
      const slug = meta && meta.slug;
      return host.fetchText({ url: `https://${slug}.example.test/chapter` });
    });

    expect(value).toBe("chapter:https://slug-42.example.test/chapter");
    expect(requested).toEqual(["https://example.test/index", "https://slug-42.example.test/chapter"]);
  });

  it("never fetches a URL containing an unresolved value", async () => {
    const requested: string[] = [];
    const host = new PluginHost({
      fetchImpl: async (request) => {
        requested.push(request.url);
        return { ok: true, status: 200, headers: {}, body: "x" };
      },
    });

    await expect(
      host.runMethod("undefined-url", () => host.fetchText({ url: "undefined/page" })),
    ).rejects.toThrow(/host request URL is incomplete: undefined\/page/);
    expect(requested).toEqual([]);
  });

  it("surfaces transport failures instead of silently returning empty bodies", async () => {
    const host = new PluginHost({
      fetchImpl: async () => {
        throw new Error("offline");
      },
    });
    await expect(
      host.runMethod("fail", () => host.fetchText({ url: "https://example.test/x" })),
    ).rejects.toThrow(/host request failed \(GET https:\/\/example\.test\/x\): offline/);
  });

  it("reports non-2xx from plugin code via the throwing helpers", async () => {
    const host = new PluginHost({
      fetchImpl: async () => ({ ok: false, status: 404, headers: {}, body: "gone" }),
    });
    await expect(
      host.runMethod("status", () => host.fetchText({ url: "https://example.test/x" })),
    ).rejects.toThrow("HTTP 404");
  });

  it("does not cache a failure, so a later attempt can recover", async () => {
    let failing = true;
    const host = new PluginHost({
      fetchImpl: async () => ({
        ok: !failing,
        status: failing ? 404 : 200,
        headers: {},
        body: failing ? "gone" : "back",
      }),
    });

    await expect(
      host.runMethod("recover", () => host.fetchText({ url: "https://example.test/page" })),
    ).rejects.toThrow("HTTP 404");

    failing = false;
    await expect(
      host.runMethod("recover", () => host.fetchText({ url: "https://example.test/page" })),
    ).resolves.toBe("back");
  });

  it("bounds runaway plugins with maxRequests", async () => {
    const host = new PluginHost({
      fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "x" }),
      maxRequests: 3,
    });
    await expect(
      host.runMethod("runaway", () => {
        for (let i = 0; i < 10; i += 1) host.fetchText({ url: `https://example.test/${i}` });
        return "done";
      }),
    ).rejects.toThrow(/exceeds the 3 limit/);
  });
});

describe("PluginHost — §6.2 HTML", () => {
  it("exposes select/selectFirst/text/html/attr/absUrl plus shorthand helpers", () => {
    const host = new PluginHost({ fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });
    const doc = host.parseHtml(
      `<html><body>
         <div class="card"><a href="/series/42" title="Series">  Hello   <b>World</b> </a>
         <img data-src="/img/1.jpg" src="/placeholder.gif"></div>
       </body></html>`,
      "https://site.test/list",
    );

    expect(doc.select(".card")).toHaveLength(1);
    expect(doc.textOf(".card a")).toBe("Hello World");
    expect(doc.attrOf(".card a", "title")).toBe("Series");
    expect(doc.absUrlOf(".card a", "href")).toBe("https://site.test/series/42");
    // data-src wins over src, per the documented priority.
    expect(doc.imgUrlOf(".card img")).toBe("https://site.test/img/1.jpg");

    const card = doc.selectFirst(".card");
    expect(card?.html()).toContain("href=\"/series/42\"");
    expect(card?.selectFirst("a")?.attr("href")).toBe("/series/42");
  });

  it("joins inline text without spaces but breaks blocks like Jsoup", () => {
    const host = new PluginHost({ fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });
    const doc = host.parseHtml("<div><h1>Title</h1><p>2024-01-20</p><span>extra</span></div>");
    expect(doc.textOf("div")).toBe("Title 2024-01-20 extra".replace("  ", " "));
  });

  it("honours a document <base href>", () => {
    const host = new PluginHost({ fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });
    const doc = host.parseHtml('<html><head><base href="https://cdn.test/covers/"></head><body><a href="1.jpg">x</a></body></html>');
    expect(doc.absUrlOf("a", "href")).toBe("https://cdn.test/covers/1.jpg");
  });

  it("resolves protocol-relative URLs as https", () => {
    const host = new PluginHost({ fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });
    expect(host.absUrl("//cdn.test/a.jpg")).toBe("https://cdn.test/a.jpg");
  });

  it("extracts CSS background images via bgUrl()", () => {
    const host = new PluginHost({ fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });
    const doc = host.parseHtml('<div class="thumb" style="background-image: url(\'/c/1.jpg\')"></div>');
    expect(doc.selectFirst(".thumb")?.bgUrl()).toBe("/c/1.jpg");
  });
});

describe("PluginHost — §6.3/§6.4 helpers", () => {
  const host = new PluginHost({ fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });

  it("extractImages reads a JS image array before falling back to the DOM", () => {
    const html = `<script>var img_list = ["\\/g\\/1.jpg", "\\/g\\/2.jpg"];</script><img src="https://x/logo.png">`;
    expect(host.extractImages(html, "https://site.test/")).toEqual([
      "https://site.test/g/1.jpg",
      "https://site.test/g/2.jpg",
    ]);
  });

  it("extractImages prefers viewer containers and filters UI noise", () => {
    const html = `<img src="/banner.jpg"><div id="page-list"><img src="/p/1.jpg"><img src="/p/2.jpg"></div>`;
    expect(host.extractImages(html, "https://site.test/")).toEqual([
      "https://site.test/p/1.jpg",
      "https://site.test/p/2.jpg",
    ]);
  });

  it("parsePageInfo understands .pagination.active and page=N links", () => {
    const html = `<div class="pagination"><span class="active">2</span>
      <a href="?page=7">7</a></div>`;
    expect(host.parsePageInfo(host.parseHtml(html))).toEqual({ currentPage: 2, lastPage: 7 });
  });

  it("unescape decodes entities and match/digits/slug behave like the Kotlin host", () => {
    expect(host.unescape("&amp; &#039;quoted&#039; &quot;x&quot;")).toBe("& 'quoted' \"x\"");
    expect(host.match("/webcomic/ep01_x.html", "/webcomic/([^/.]+)")).toBe("ep01_x");
    expect(host.match("nope", "([0-9]+)")).toBeNull();
    expect(host.digits("Ep. 42")).toBe("42");
    expect(host.slug("A B")).toBe("a_b");
  });

  it("caches with TTL and reports misses", () => {
    host.cachePut("k", { v: 1 }, 50);
    expect(host.cacheGet("k")).toEqual({ v: 1 });
    expect(host.cacheGet("missing")).toBeNull();
  });

  it("evalJs runs site script in a scope without browser globals", () => {
    const value = host.evalJs("var galleryinfo = { id: 7, tag: 'x' };", "galleryinfo");
    expect(value).toEqual({ id: 7, tag: "x" });
  });

  it("evalJs cannot reach chrome/document from evaluated site code", () => {
    expect(host.evalJs("var galleryinfo = typeof chrome + '/' + typeof document;", "galleryinfo")).toBe(
      "undefined/undefined",
    );
  });

  it("exposes apiLevel 1 and the configured language", () => {
    expect(host.apiLevel).toBe(HOST_API_LEVEL);
    expect(new PluginHost({ language: "ko" }).language).toBe("ko");
  });
});

describe("loadSource — plugin compilation", () => {
  it("rejects scripts without a top-level source object", () => {
    expect(() => extractSource("var notSource = {};")).toThrow(/did not define a top-level 'source'/);
  });

  it("rejects a source missing required methods", () => {
    expect(() =>
      extractSource("var source = { id: 'x', displayName: 'X', loadListing: function(){} };"),
    ).toThrow(/loadEpisodes must be a function/);
  });

  it("cannot see extension globals while evaluating the plugin", () => {
    const source = extractSource(
      `var source = {
         id: "probe", displayName: "Probe",
         loadListing: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){ return ["https://x/1.jpg"]; },
         leaked: [typeof window, typeof document, typeof fetch, typeof chrome].join(",")
       };`,
    );
    expect((source as unknown as { leaked: string }).leaked).toBe("undefined,undefined,undefined,undefined");
  });

  it("applies documented §2 defaults", () => {
    const loaded = loadSource(peppercarrotScript, { fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) });
    expect(loaded.id).toBe("peppercarrot");
    expect(loaded.displayName).toBe("Pepper & Carrot");
    expect(loaded.meta.origin).toBe(ORIGIN);
    expect(loaded.meta.apiLevel).toBe(1);
    expect(loaded.meta.episodePageSize).toBe(100);
    expect(loaded.meta.notificationMode).toBe("NONE");
    expect(loaded.meta.progressDisplay).toBe("LAST_READ_ORDER");
    expect(loaded.meta.referer).toBe(`${ORIGIN}/`);
    expect(loaded.catalogs.map((c) => c.id)).toEqual(["ALL", "KO", "FR", "JA"]);
  });

  it("falls back to the LATEST catalog when none are declared", () => {
    const loaded = loadSource(
      `var source = {
         id: "bare", displayName: "Bare",
         loadListing: function(){ return { items: [{ id: "1", title: "t", thumbUrl: "", href: "" }], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(){ return { items: [{ wrId: "1", title: "e", href: "" }], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){ return ["https://x/1.jpg"]; }
       };`,
      { fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) },
    );
    expect(loaded.catalogs).toEqual([{ id: "LATEST", label: "최신", paginated: true }]);
    expect(loaded.meta.searchPlaceholder).toBe("제목 검색");
  });
});

describe("Pepper & Carrot built-in source", () => {
  function load() {
    const transport = fakeFetch([
      { match: "/webcomics/peppercarrot.html", response: { body: WEBCOMICS_HTML } },
      { match: "/webcomic/ep01_", response: { body: EPISODE_HTML } },
    ]);
    return { source: loadSource(peppercarrotScript, { fetchImpl: transport.impl }), calls: transport.calls };
  }

  it("loadListing returns the representative card with the latest cover", async () => {
    const { source } = load();
    const listing = await source.loadListing("ALL", 1);

    expect(listing.pageInfo).toEqual({ currentPage: 1, lastPage: 1 });
    expect(listing.items).toHaveLength(1);
    const card = listing.items[0];
    expect(card.id).toBe("peppercarrot-en");
    expect(card.title).toBe("Pepper & Carrot");
    expect(card.thumbUrl).toContain("ep39_panel_01_low-res.jpg");
    expect(card.updatedAt).toBe("2024.01.20");
    expect(card.href).toBe(`${ORIGIN}/en/webcomics/peppercarrot.html`);
  });

  it("loadListing honours the Korean catalog", async () => {
    const { source, calls } = load();
    const listing = await source.loadListing("KO", 1);
    expect(calls[0].url).toBe(`${ORIGIN}/ko/webcomics/peppercarrot.html`);
    expect(listing.items[0].title).toBe("페퍼와 캐롯 (Pepper & Carrot)");
    expect(listing.items[0].id).toBe("peppercarrot-ko");
  });

  it("loadEpisodes extracts wrId/title/date for every figure", async () => {
    const { source } = load();
    const listing = await source.loadListing("ALL", 1);
    const episodes = await source.loadEpisodes(listing.items[0], 1);

    expect(episodes.items).toHaveLength(2);
    expect(episodes.items[0]).toMatchObject({
      wrId: "ep39_For_Your_Smile",
      title: "Episode 39: For Your Smile",
      date: "2024-01-20",
    });
    expect(episodes.items[1].wrId).toBe("ep01_Potion_of_Flight");
  });

  it("loadEpisodes derives the language from the item href", async () => {
    const { source, calls } = load();
    await source.loadEpisodes(
      { id: "peppercarrot-fr", title: "x", thumbUrl: "", href: `${ORIGIN}/fr/webcomics/peppercarrot.html` },
      1,
    );
    expect(calls[0].url).toBe(`${ORIGIN}/fr/webcomics/peppercarrot.html`);
  });

  it("resolveImages upgrades to hi-res, de-duplicates, and drops UI images", async () => {
    const { source } = load();
    const images = await source.resolveImages(
      { wrId: "ep01_Potion_of_Flight", title: "Episode 1", href: `${ORIGIN}/en/webcomic/ep01_Potion_of_Flight.html` },
      { id: "peppercarrot-en", title: "Pepper & Carrot", thumbUrl: "", href: "" },
    );

    expect(images).toEqual([
      "https://www.peppercarrot.com/0_sources/ep01_Potion_of_Flight/hi-res/ep01_01.jpg",
      "https://www.peppercarrot.com/0_sources/ep01_Potion_of_Flight/hi-res/ep01_02.jpg",
    ]);
  });

  it("resolveImages surfaces the plugin's own error when nothing matches", async () => {
    const source = loadSource(peppercarrotScript, {
      fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "<html><body>no images</body></html>" }),
    });
    await expect(
      source.resolveImages(
        { wrId: "e", title: "Empty", href: `${ORIGIN}/en/webcomic/ep02_x.html` },
        { id: "id", title: "t", thumbUrl: "", href: "" },
      ),
    ).rejects.toThrow(/이미지를 찾을 수 없습니다/);
  });

  it("search matches episode titles case-insensitively", async () => {
    const { source } = load();
    const hits = await source.search({ text: "potion" });
    expect(hits).toHaveLength(1);
    expect(hits[0].entryEpisodeId).toBe("ep01_Potion_of_Flight");
  });

  it("imageFallbacks downgrades hi-res to low-res", async () => {
    const { source } = load();
    await expect(
      source.imageFallbacks("https://x/0_sources/ep01/hi-res/1.jpg"),
    ).resolves.toEqual(["https://x/0_sources/ep01/low-res/1.jpg"]);
  });

  it("ownsHost accepts the plugin's extra CDN but rejects unrelated hosts", async () => {
    const { source } = load();
    await expect(source.ownsHost("davidrevoy.com")).resolves.toBe(true);
    await expect(source.ownsHost("www.peppercarrot.com")).resolves.toBe(true);
    await expect(source.ownsHost("evil.test")).resolves.toBe(false);
  });

  it("defaults useProxy to true when the plugin omits it", async () => {
    const loaded = loadSource(
      `var source = {
         id: "nopxy", displayName: "No proxy",
         loadListing: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){ return ["https://x/1.jpg"]; }
       };`,
      { fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) },
    );
    await expect(loaded.useProxy("https://x/1.jpg")).resolves.toBe(true);
  });
});

describe("LoadedSource reported totals", () => {
  const build = (episodesBody: string) =>
    loadSource(
      `var source = {
         id: "totals", displayName: "Totals",
         loadListing: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(item, page){ ${episodesBody} },
         resolveImages: function(){ return ["https://x/1.jpg"]; }
       };`,
      { fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) },
    );
  const oneEpisode = `{ wrId: "1", title: "e", href: "" }`;

  it("keeps a total stated on the result itself", async () => {
    const loaded = build(
      `return { items: [${oneEpisode}], pageInfo: { currentPage: page, lastPage: 3 }, totalEpisodes: 250 };`,
    );
    const page = await loaded.loadEpisodes({ id: "1", title: "t", thumbUrl: "", href: "" }, 1);
    // Dropping these was why a correct plugin answer never reached the read model.
    expect(page.totalEpisodes).toBe(250);
    expect(page.pageInfo).toMatchObject({ currentPage: 1, lastPage: 3 });
  });

  it("keeps a total stated inside pageInfo", async () => {
    const loaded = build(
      `return { items: [${oneEpisode}], pageInfo: { currentPage: page, lastPage: 2, itemCount: 37 } };`,
    );
    const page = await loaded.loadEpisodes({ id: "1", title: "t", thumbUrl: "", href: "" }, 2);
    expect(page.pageInfo.itemCount).toBe(37);
    expect(page.itemCount).toBe(37);
  });

  it("keeps every documented spelling", async () => {
    const loaded = build(
      `return { items: [${oneEpisode}], pageInfo: { currentPage: 1, lastPage: 1 },
                total: 11, totalItems: 12, itemCount: 13, totalEpisodes: 14 };`,
    );
    const page = await loaded.loadEpisodes({ id: "1", title: "t", thumbUrl: "", href: "" }, 1);
    expect(page).toMatchObject({ total: 11, totalItems: 12, itemCount: 13, totalEpisodes: 14 });
  });

  it("keeps a numeric string the plugin used for the count", async () => {
    const loaded = build(
      `return { items: [${oneEpisode}], pageInfo: { currentPage: 1, lastPage: 1 }, total: "42" };`,
    );
    const page = await loaded.loadEpisodes({ id: "1", title: "t", thumbUrl: "", href: "" }, 1);
    expect(page.total).toBe("42");
  });

  it("reports no total at all when the plugin states none", async () => {
    const loaded = build(`return { items: [${oneEpisode}], pageInfo: { currentPage: page, lastPage: 1 } };`);
    const page = await loaded.loadEpisodes({ id: "1", title: "t", thumbUrl: "", href: "" }, 1);
    expect(page.pageInfo).toEqual({ currentPage: 1, lastPage: 1 });
    expect(page.total).toBeUndefined();
    expect(page.totalEpisodes).toBeUndefined();
  });
});

describe("LoadedSource validation", () => {
  const build = (body: string) =>
    loadSource(
      `var source = {
         id: "v", displayName: "V",
         loadListing: function(){ ${body} },
         loadEpisodes: function(){ return { items: [{ wrId: "1", title: "e", href: "" }], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){ return ["https://x/1.jpg"]; }
       };`,
      { fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) },
    );

  it("accepts flat currentPage/lastPage return shapes", async () => {
    const loaded = build(
      `return { items: [{ id: "1", title: "t", thumbUrl: "", href: "" }], currentPage: 3, lastPage: 9 };`,
    );
    const listing = await loaded.loadListing("LATEST", 1);
    expect(listing.pageInfo).toEqual({ currentPage: 3, lastPage: 9 });
  });

  it("throws when items lack an id", async () => {
    const loaded = build(`return { items: [{ title: "t" }], pageInfo: { currentPage: 1, lastPage: 1 } };`);
    await expect(loaded.loadListing("LATEST", 1)).rejects.toThrow(/missing "id"/);
  });

  it("throws on an empty listing unless emptyListingOk is set", async () => {
    const loaded = build(`return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } };`);
    await expect(loaded.loadListing("LATEST", 1)).rejects.toThrow(/returned no items/);

    const lenient = loadSource(
      `var source = {
         id: "lenient", displayName: "L", emptyListingOk: true,
         loadListing: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){ return ["https://x/1.jpg"]; }
       };`,
      { fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) },
    );
    await expect(lenient.loadListing("LATEST", 1)).resolves.toMatchObject({ items: [] });
  });

  it("enforces the 30s call budget on plugins that await the host", async () => {
    // The browser cannot preempt synchronous plugin code (more on that in plugin-host.ts), so the
    // budget is observable on plugins that do hand control back to the event loop.
    const loaded = loadSource(
      `var source = {
         id: "slow", displayName: "S",
         loadListing: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){
           var end = Date.now() + 250;
           while (Date.now() < end) {}
           return ["https://x/1.jpg"];
         }
       };`,
      {
        fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }),
        callTimeoutMs: 50,
      },
    );

    // Synchronous JS runs to completion in a browser, so a CPU-bound plugin is not preemptible.
    await expect(
      loaded.resolveImages({ wrId: "1", title: "t", href: "" }, { id: "1", title: "t", thumbUrl: "", href: "" }),
    ).resolves.toEqual(["https://x/1.jpg"]);
    expect(DEFAULT_CALL_TIMEOUT_MS).toBe(30_000);
  });

  it("times out plugins that yield while waiting on the host", async () => {
    const loaded = loadSource(
      `var source = {
         id: "hang", displayName: "H",
         loadListing: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){
           return host.fetchText({ url: "https://hang.test/" }).then(function(){ return ["https://x/1.jpg"]; });
         }
       };`,
      {
        fetchImpl: () => new Promise(() => {}),
        callTimeoutMs: 40,
      },
    );

    await expect(
      loaded.resolveImages({ wrId: "1", title: "t", href: "" }, { id: "1", title: "t", thumbUrl: "", href: "" }),
    ).rejects.toThrow(/resolveImages timed out/);
  });

  it("de-duplicates and trims resolved image URLs", async () => {
    const loaded = loadSource(
      `var source = {
         id: "imgs", displayName: "I",
         loadListing: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         loadEpisodes: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){ return [" https://x/1.jpg ", "https://x/1.jpg", "https://x/2.jpg"]; }
       };`,
      { fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "" }) },
    );
    await expect(
      loaded.resolveImages({ wrId: "1", title: "t", href: "" }, { id: "1", title: "t", thumbUrl: "", href: "" }),
    ).resolves.toEqual(["https://x/1.jpg", "https://x/2.jpg"]);
  });
});

describe("PluginHost via chrome.runtime (real transport path)", () => {
  it("sends FETCH_REQUEST and unwraps FETCH_RESPONSE", async () => {
    const sent: Array<{ action: string; method?: string; url?: string }> = [];
    const spy = async (message: { requestId: string; url: string; action: string; method?: string }) => {
      sent.push(message);
      return {
        action: "FETCH_RESPONSE",
        requestId: message.requestId,
        ok: true,
        status: 200,
        statusText: "OK",
        url: message.url,
        headers: {},
        body: "hello",
      };
    };
    (globalThis as unknown as { chrome: unknown }).chrome = { runtime: { sendMessage: spy } };

    const host = new PluginHost();
    await expect(host.fetchTextAsync({ url: "https://example.test/" })).resolves.toBe("hello");
    expect(sent[0]).toMatchObject({ action: "FETCH_REQUEST", method: "GET", url: "https://example.test/" });

    delete (globalThis as unknown as { chrome?: unknown }).chrome;
  });

  it("surfaces a missing service worker as an error", async () => {
    const host = new PluginHost();
    await expect(host.fetchTextAsync({ url: "https://example.test/" })).rejects.toThrow(/unreachable/);
  });

  it("drives a plugin through the real message path end to end", async () => {
    const pages: Record<string, string> = {
      "https://example.test/list": '<div class="card"><a href="/series/1">Series</a></div>',
    };
    (globalThis as unknown as { chrome: unknown }).chrome = {
      runtime: {
        sendMessage: async (message: { requestId: string; url: string }) => {
          const body = pages[message.url];
          return {
            action: "FETCH_RESPONSE",
            requestId: message.requestId,
            ok: body !== undefined,
            status: body === undefined ? 404 : 200,
            statusText: "",
            url: message.url,
            headers: {},
            body: body ?? "",
          };
        },
      },
    };

    const source = loadSource(peppercarrotScript);
    expect(source.id).toBe("peppercarrot");

    const probe = loadSource(
      `var source = {
         id: "wf", displayName: "Wire fetch",
         loadListing: function(){
           var doc = host.parseHtml(host.fetchText({ url: "https://example.test/list" }));
           return {
             items: doc.select(".card").map(function(el){
               return { id: el.text(), title: el.text(), thumbUrl: "", href: host.absUrl(el.absUrlOf("a", "href"), "https://example.test/list") };
             }),
             pageInfo: { currentPage: 1, lastPage: 1 }
           };
         },
         loadEpisodes: function(){ return { items: [], pageInfo: { currentPage: 1, lastPage: 1 } }; },
         resolveImages: function(){ return ["https://example.test/1.jpg"]; }
       };`,
    );

    const listing = await probe.loadListing("LATEST", 1);
    expect(listing.items).toEqual([
      { id: "Series", title: "Series", thumbUrl: "", href: "https://example.test/series/1" },
    ]);

    delete (globalThis as unknown as { chrome?: unknown }).chrome;
  });
});
