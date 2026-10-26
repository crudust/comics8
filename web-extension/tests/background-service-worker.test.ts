/**
 * Unit tests for the background service worker contract.
 *
 * They run in Node with a hand-rolled `fetch` and a fake `chrome` object, which is exactly the
 * surface the service worker depends on — no DOM, no browser.
 */

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  DEFAULT_MAX_BYTES,
  bytesToBase64,
  installFetchHandler,
  isFetchRequest,
  performFetch,
  type FetchRequest,
  type FetchResult,
} from "../src/background/fetch-handler";
import {
  MAX_REFERER_RULES,
  buildRule,
  ensureRefererRule,
  hostnameOf,
  installRefererRuleHandler,
  listRefererRules,
  normalizeReferer,
  normalizeUserAgent,
  reconcileRefererRules,
  resetRefererRuleCache,
} from "../src/background/referer-rules";
import {
  READER_PAGE_PATH,
  focusOrOpenReader,
  installActionHandler,
} from "../src/background/action-handler";
import { installDom, resetObjectUrls } from "./harness";

installDom();

type FakeRule = chrome.declarativeNetRequest.Rule;

/** Minimal in-memory stand-in for the `chrome` APIs the worker uses. */
function installChrome(): { rules: Map<number, FakeRule>; store: Map<string, unknown> } {
  const rules = new Map<number, FakeRule>();
  const store = new Map<string, unknown>();

  const chromeStub = {
    runtime: {
      onMessage: { addListener: vi.fn(), removeListener: vi.fn() },
      onInstalled: { addListener: vi.fn() },
      onStartup: { addListener: vi.fn() },
    },
    storage: {
      local: {
        get: async (key: string) => ({ [key]: store.get(key) }),
        set: async (values: Record<string, unknown>) => {
          for (const [key, value] of Object.entries(values)) store.set(key, value);
        },
      },
    },
    declarativeNetRequest: {
      getDynamicRules: async () => [...rules.values()],
      updateDynamicRules: async ({
        removeRuleIds = [],
        addRules = [],
      }: chrome.declarativeNetRequest.UpdateRuleOptions) => {
        for (const id of removeRuleIds) rules.delete(id);
        for (const rule of addRules) {
          if (rules.has(rule.id)) throw new Error(`duplicate rule id ${rule.id}`);
          rules.set(rule.id, rule);
        }
      },
    },
  };

  (globalThis as unknown as { chrome: unknown }).chrome = chromeStub;
  return { rules, store };
}

function request(overrides: Partial<FetchRequest> = {}): FetchRequest {
  return {
    action: "FETCH_REQUEST",
    url: "https://example.test/page.html",
    method: "GET",
    headers: {},
    ...overrides,
  };
}

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

afterEach(() => {
  delete (globalThis as unknown as { chrome?: unknown }).chrome;
  resetObjectUrls();
  // The worker memoises the installed rule set; tests swap the backing `chrome` per case.
  resetRefererRuleCache();
});

describe("isFetchRequest", () => {
  it("accepts only FETCH_REQUEST messages with a URL", () => {
    expect(isFetchRequest(request())).toBe(true);
    expect(isFetchRequest({ action: "FETCH_REQUEST" })).toBe(false);
    expect(isFetchRequest({ action: "OTHER", url: "https://a.test" })).toBe(false);
    expect(isFetchRequest(request({ url: "   " }))).toBe(false);
    expect(isFetchRequest(null)).toBe(false);
  });
});

describe("performFetch", () => {
  it("returns the envelope `sendToBackground()` validates", async () => {
    installChrome();
    const fetchImpl = vi.fn(async () => jsonResponse({ hello: "world" }));

    const result = await performFetch(request({ requestId: "req-1" }), fetchImpl);

    expect(result.action).toBe("FETCH_RESPONSE");
    expect(result.requestId).toBe("req-1");
    expect(result.ok).toBe(true);
    expect(result.status).toBe(200);
    expect(result.body).toBe('{"hello":"world"}');
    expect(result.data).toBe('{"hello":"world"}');
  });

  it("parses JSON only when asked, and reports invalid JSON without throwing", async () => {
    installChrome();
    const good = await performFetch(
      request({ responseType: "json" }),
      async () => jsonResponse({ a: 1 }),
    );
    expect(good.json).toEqual({ a: 1 });
    expect(good.data).toEqual({ a: 1 });

    const bad = await performFetch(
      request({ responseType: "json" }),
      async () => new Response("not json", { status: 200 }),
    );
    expect(bad.ok).toBe(true);
    expect(bad.json).toBeUndefined();
    expect(bad.error).toMatch(/Invalid JSON/);
  });

  it("keeps binary bodies intact and offers data URLs", async () => {
    installChrome();
    const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0x00, 0x10]);
    const imageResponse = (): Response =>
      new Response(bytes, { status: 200, headers: { "content-type": "image/jpeg" } });

    const base64 = await performFetch(request({ responseType: "base64" }), async () => imageResponse());
    expect(base64.bodyBase64).toBe(bytesToBase64(bytes));
    expect(base64.body).toBe("");

    const dataUrl = await performFetch(
      request({ responseType: "data-url" }),
      async () => imageResponse(),
    );
    expect(dataUrl.dataUrl).toBe(`data:image/jpeg;base64,${bytesToBase64(bytes)}`);
    expect(dataUrl.data).toBe(dataUrl.dataUrl);
  });

  it("never lets JavaScript set forbidden or framing headers", async () => {
    installChrome();
    const seen: RequestInit[] = [];
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      seen.push(init ?? {});
      return new Response("ok");
    });

    await performFetch(
      request({
        headers: {
          Referer: "https://manga.test/reader",
          "User-Agent": "Mozilla/5.0",
          Cookie: "session=secret",
          Host: "evil.test",
          "Content-Length": "12",
          "X-Trace": "keep-me",
          "Sec-Fetch-Site": "same-origin",
        },
      }),
      fetchImpl,
    );

    expect(seen).toHaveLength(1);
    expect(seen[0].headers).toEqual({ "x-trace": "keep-me" });
    // Cookies must never be attached: the extension's fetches are cross-site.
    expect(seen[0].credentials).toBe("omit");
  });

  it("installs a referer rule before issuing the request", async () => {
    const { rules } = installChrome();
    let installedBeforeFetch = 0;
    const fetchImpl = vi.fn(async () => {
      installedBeforeFetch = rules.size;
      return new Response("ok");
    });

    await performFetch(
      request({ url: "https://img.manga.test/01.jpg", referer: "https://manga.test/" }),
      fetchImpl,
    );

    expect(installedBeforeFetch).toBe(1);
    const [rule] = [...rules.values()];
    expect(rule.action.requestHeaders).toEqual([
      { header: "Referer", operation: "set", value: "https://manga.test/" },
    ]);
    expect(rule.condition.requestDomains).toEqual(["img.manga.test"]);
  });

  it("rejects non-http(s) schemes and invalid URLs without calling fetch", async () => {
    installChrome();
    const fetchImpl = vi.fn(async () => new Response("never"));

    const ftp = await performFetch(request({ url: "ftp://example.test/a.zip" }), fetchImpl);
    expect(ftp.ok).toBe(false);
    expect(ftp.error).toMatch(/Unsupported URL scheme/);

    const broken = await performFetch(request({ url: "not a url" }), fetchImpl);
    expect(broken.ok).toBe(false);
    expect(broken.error).toMatch(/Invalid URL/);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("turns transport failures into a status-0 envelope", async () => {
    installChrome();
    const result = await performFetch(
      request({ requestId: "req-2" }),
      async () => {
        throw new Error("network down");
      },
    );

    expect(result.ok).toBe(false);
    expect(result.status).toBe(0);
    expect(result.requestId).toBe("req-2");
    expect(result.error).toBe("network down");
  });

  it("refuses bodies above the configured limit before buffering them", async () => {
    installChrome();
    const result = await performFetch(
      request({ maxBytes: 4 }),
      async () => new Response("1234567890", { headers: { "content-length": "10" } }),
    );

    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/above the 4 byte limit/);
    expect(DEFAULT_MAX_BYTES).toBeGreaterThan(1024);
  });

  it("handles HEAD and 204 responses without touching the body", async () => {
    installChrome();
    const head = await performFetch(request({ method: "HEAD" }), async () =>
      new Response(null, { status: 200, headers: { "content-length": "999" } }),
    );
    expect(head.status).toBe(200);
    expect(head.body).toBe("");

    const noContent = await performFetch(request(), async () => new Response(null, { status: 204 }));
    expect(noContent.body).toBe("");
    expect(noContent.ok).toBe(true);
  });
});

describe("referer rules", () => {
  it("normalises domains and URLs to ASCII hostnames", () => {
    expect(hostnameOf("https://Manga.Test/reader?p=1")).toBe("manga.test");
    expect(hostnameOf("manga.test")).toBe("manga.test");
    expect(hostnameOf(" manga.test. ")).toBe("manga.test");
    expect(hostnameOf("ftp://manga.test")).toBeNull();
    expect(hostnameOf("*.manga.test")).toBeNull();
    expect(hostnameOf("")).toBeNull();
  });

  it("rejects header values that would smuggle extra headers", () => {
    expect(() => normalizeReferer("https://manga.test/\r\nX-Evil: 1")).toThrow(/CR\/LF/);
    expect(() => normalizeUserAgent("Mozilla/5.0\r\nX-Evil: 1")).toThrow(/CR\/LF/);
    expect(normalizeReferer("manga.test")).toBe("https://manga.test/");
    expect(normalizeUserAgent("  Mozilla/5.0  ")).toBe("Mozilla/5.0");
  });

  it("merges per domain and rewrites the whole rule set atomically", async () => {
    const { rules, store } = installChrome();

    await ensureRefererRule({
      url: "https://a.test/1.jpg",
      referer: "https://a.test/",
    });
    await ensureRefererRule({
      url: "https://a.test/2.jpg",
      userAgent: "UA/1",
    });

    expect(rules.size).toBe(1);
    const rule = [...rules.values()][0];
    expect(rule.action.requestHeaders).toEqual([
      { header: "Referer", operation: "set", value: "https://a.test/" },
      { header: "User-Agent", operation: "set", value: "UA/1" },
    ]);
    expect(store.has("comics8.refererRules")).toBe(true);
  });

  it("merges concurrent updates instead of losing one", async () => {
    const { rules } = installChrome();

    await Promise.all([
      ensureRefererRule({ url: "https://a.test/", referer: "https://a.test/" }),
      ensureRefererRule({ url: "https://b.test/", referer: "https://b.test/" }),
      ensureRefererRule({ url: "https://c.test/", referer: "https://c.test/" }),
    ]);

    expect(rules.size).toBe(3);
    expect([...rules.values()].map((rule) => rule.condition.requestDomains?.[0]).sort()).toEqual([
      "a.test",
      "b.test",
      "c.test",
    ]);
  });

  it("keeps ids inside the reserved range and below the quota", () => {
    const rule = buildRule(9000 + MAX_REFERER_RULES - 1, {
      domain: "manga.test",
      referer: "https://manga.test/",
    });
    expect(rule.id).toBe(9000 + MAX_REFERER_RULES - 1);
    expect(rule.action.type).toBe("modifyHeaders");
    expect(rule.priority).toBe(1);
  });

  it("reconciles stored specs into dynamic rules on startup", async () => {
    const { rules, store } = installChrome();
    store.set("comics8.refererRules", [
      { domain: "manga.test", referer: "https://manga.test/" },
      { domain: "bad host", referer: "https://manga.test/" },
      { domain: "comic.test", userAgent: "UA/2" },
    ]);

    // Simulate a stale rule left behind by an older version.
    rules.set(9000, buildRule(9000, { domain: "stale.test", referer: "https://stale.test/" }));

    const specs = await reconcileRefererRules();

    expect(specs.map((spec) => spec.domain)).toEqual(["manga.test", "comic.test"]);
    expect([...rules.values()].map((rule) => rule.condition.requestDomains?.[0])).toEqual([
      "manga.test",
      "comic.test",
    ]);
    expect(await listRefererRules()).toHaveLength(2);
  });

  it("does not re-install rules for every image of the same domain", async () => {
    const { rules } = installChrome();
    const update = vi.spyOn(
      (globalThis as unknown as { chrome: typeof chrome }).chrome.declarativeNetRequest,
      "updateDynamicRules",
    );

    for (let page = 0; page < 25; page += 1) {
      await ensureRefererRule({
        url: `https://img.manga.test/${page}.jpg`,
        referer: "https://manga.test/",
      });
    }

    expect(rules.size).toBe(1);
    expect(update).toHaveBeenCalledTimes(1);
  });
});

describe("onMessage wiring", () => {
  type Listener = (
    message: unknown,
    sender: unknown,
    sendResponse: (response: unknown) => void,
  ) => boolean | undefined;

  /** Captures listeners so the message contract can be asserted without a browser. */
  function installEventChrome(): { listeners: Listener[]; removed: Listener[]; store: Map<string, unknown>; rules: Map<number, FakeRule> } {
    const listeners: Listener[] = [];
    const removed: Listener[] = [];
    const rules = new Map<number, FakeRule>();
    const store = new Map<string, unknown>();

    const chromeStub = {
      runtime: {
        onMessage: {
          addListener: (listener: Listener) => listeners.push(listener),
          removeListener: (listener: Listener) => removed.push(listener),
        },
        onInstalled: { addListener: vi.fn(), removeListener: vi.fn() },
        onStartup: { addListener: vi.fn(), removeListener: vi.fn() },
      },
      storage: {
        local: {
          get: async (key: string) => ({ [key]: store.get(key) }),
          set: async (values: Record<string, unknown>) => {
            for (const [key, value] of Object.entries(values)) store.set(key, value);
          },
        },
      },
      declarativeNetRequest: {
        getDynamicRules: async () => [...rules.values()],
        updateDynamicRules: async ({
          removeRuleIds = [],
          addRules = [],
        }: chrome.declarativeNetRequest.UpdateRuleOptions) => {
          for (const id of removeRuleIds) rules.delete(id);
          for (const rule of addRules) rules.set(rule.id, rule);
        },
      },
    };

    (globalThis as unknown as { chrome: unknown }).chrome = chromeStub;
    return { listeners, removed, store, rules };
  }

  it("installs one FETCH_REQUEST listener and unsubscribes cleanly", async () => {
    const { listeners, removed } = installEventChrome();
    const uninstall = installFetchHandler({ fetchImpl: async () => new Response("ok") });

    expect(listeners).toHaveLength(1);
    expect(removed).toHaveLength(0);

    const reply = await new Promise<FetchResult>((resolve) => {
      const keepOpen = listeners[0](request({ requestId: "wired" }), {}, (response) => {
        resolve(response as FetchResult);
      });
      // A `false` return would tell Chrome nobody will answer.
      expect(keepOpen).toBe(true);
    });

    expect(reply.action).toBe("FETCH_RESPONSE");
    expect(reply.requestId).toBe("wired");
    expect(reply.body).toBe("ok");

    uninstall();
    expect(removed).toEqual(listeners);
  });

  it("ignores messages that are not FETCH_REQUEST", () => {
    const { listeners } = installEventChrome();
    installFetchHandler();

    expect(listeners[0]({ action: "SOMETHING_ELSE" }, {}, () => undefined)).toBe(false);
    expect(listeners[0](null, {}, () => undefined)).toBe(false);
  });

  it("answers SET_REFERER_RULE and GET_REFERER_RULES", async () => {
    const { listeners, store, rules } = installEventChrome();
    installRefererRuleHandler();

    const setReply = await new Promise<Record<string, unknown>>((resolve) => {
      const keepOpen = listeners[0](
        {
          action: "SET_REFERER_RULE",
          domain: "manga.test",
          referer: "https://manga.test/",
          userAgent: "UA/9",
        },
        {},
        (response) => resolve(response as Record<string, unknown>),
      );
      expect(keepOpen).toBe(true);
    });

    expect(setReply.ok).toBe(true);
    expect(setReply.rules).toEqual([
      { domain: "manga.test", referer: "https://manga.test/", userAgent: "UA/9" },
    ]);
    expect(rules.size).toBe(1);
    expect(store.get("comics8.refererRules")).toEqual(setReply.rules);

    const getReply = await new Promise<Record<string, unknown>>((resolve) => {
      listeners[0]({ action: "GET_REFERER_RULES" }, {}, (response) =>
        resolve(response as Record<string, unknown>),
      );
    });
    expect(getReply).toMatchObject({ ok: true, action: "GET_REFERER_RULES" });
    expect(getReply.rules).toHaveLength(1);
  });

  it("reports bad SET_REFERER_RULE payloads instead of throwing", async () => {
    const { listeners } = installEventChrome();
    installRefererRuleHandler();

    const reply = await new Promise<Record<string, unknown>>((resolve) => {
      listeners[0]({ action: "SET_REFERER_RULE", domain: "not a host" }, {}, (response) =>
        resolve(response as Record<string, unknown>),
      );
    });

    expect(reply.ok).toBe(false);
    expect(String(reply.error)).toMatch(/valid http\(s\) domain/);
  });
});

describe("toolbar action", () => {
  const READER_URL = "chrome-extension://comics8/src/index.html";

  /** Captures the `chrome.action.onClicked` listener and the `chrome.tabs` calls it makes. */
  function installActionChrome(tabs: Array<{ id?: number; url?: string }> = []) {
    const clicked: Array<() => void> = [];
    const removed: Array<() => void> = [];
    const created: Array<{ url?: string }> = [];
    const updated: Array<{ id: number; props: Record<string, unknown> }> = [];

    (globalThis as unknown as { chrome: unknown }).chrome = {
      action: {
        onClicked: {
          addListener: (listener: () => void) => clicked.push(listener),
          removeListener: (listener: () => void) => removed.push(listener),
        },
      },
      tabs: {
        query: async () => tabs,
        create: async (props: { url?: string }) => {
          created.push(props);
          return { id: 99 };
        },
        update: async (id: number, props: Record<string, unknown>) => {
          updated.push({ id, props });
          return { id };
        },
      },
    };

    return { clicked, removed, created, updated };
  }

  it("opens the reader tab when none is open yet", async () => {
    const { clicked, created, updated } = installActionChrome();
    const uninstall = installActionHandler(READER_URL);

    expect(clicked).toHaveLength(1);
    clicked[0]();

    await vi.waitFor(() => expect(created).toEqual([{ url: READER_URL }]));
    expect(updated).toEqual([]);
    uninstall();
  });

  it("reuses the reader tab that is already open instead of adding another", async () => {
    const { clicked, created, updated } = installActionChrome([
      { id: 1, url: "https://example.test/" },
      { id: 7, url: READER_URL },
    ]);
    installActionHandler(READER_URL);

    clicked[0]();

    await vi.waitFor(() => expect(updated).toEqual([{ id: 7, props: { active: true } }]));
    expect(created).toEqual([]);
  });

  it("opens the reader when the only tab is a different extension page", async () => {
    const { clicked, created } = installActionChrome([
      { id: 1, url: "chrome-extension://comics8/src/sandbox.html" },
    ]);
    installActionHandler(READER_URL);

    clicked[0]();

    await vi.waitFor(() => expect(created).toEqual([{ url: READER_URL }]));
  });

  it("points at src/index.html and removes its listener on dispose", () => {
    expect(READER_PAGE_PATH).toBe("src/index.html");

    const { clicked, removed } = installActionChrome();
    const uninstall = installActionHandler(READER_URL);
    uninstall();

    expect(removed).toEqual(clicked);
  });

  it("survives a missing chrome.action (dev-server page)", () => {
    (globalThis as unknown as { chrome: unknown }).chrome = {
      tabs: { query: async () => [], create: async () => ({}), update: async () => ({}) },
    };
    expect(() => installActionHandler(READER_URL)()).not.toThrow();
  });

  it("focusOrOpenReader is the primitive both paths use", async () => {
    const { created, updated } = installActionChrome([{ id: 3, url: `${READER_URL}#/read` }]);
    const tabs = (globalThis as unknown as { chrome: typeof chrome }).chrome.tabs;

    await focusOrOpenReader(READER_URL, tabs);
    expect(updated).toEqual([{ id: 3, props: { active: true } }]);
    expect(created).toEqual([]);
  });

  it("reports tab failures without rejecting the click handler", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    (globalThis as unknown as { chrome: unknown }).chrome = {
      action: {
        onClicked: { addListener: (listener: () => void) => (clickedListener = listener), removeListener: () => {} },
      },
      tabs: {
        query: async () => {
          throw new Error("tabs unavailable");
        },
        create: async () => ({}),
        update: async () => ({}),
      },
    };
    let clickedListener: (() => void) | null = null;

    installActionHandler(READER_URL);
    clickedListener!();

    await vi.waitFor(() => expect(error).toHaveBeenCalled());
    error.mockRestore();
  });
});
