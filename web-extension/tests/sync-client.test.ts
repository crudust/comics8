/**
 * Sync client: endpoints, auth headers, pairing flow, persistence, error mapping.
 *
 * Shapes asserted here are the ones `server/app/sync.py` actually reads/writes
 * (`import_snapshot_for_user`, `emit_favorite`, `emit_history`, `emit_read_episode`, `emit_setting`,
 * `pair_request`, `pair_confirm`).
 */

import { afterEach, describe, expect, it } from "vitest";
import type { SyncClientOptions } from "../src/sync/sync-client";
import {
  DEFAULT_SERVER_URL,
  EMPTY_STATUS,
  MAX_CATALOG_BATCH_ITEMS,
  MAX_SYNC_ITEMS_PER_REQUEST,
  SYNC_STORAGE_KEY,
  SyncClient,
  SyncError,
  emptyUpdates,
  generateSyncKey,
  normalizeApiRoot,
} from "../src/sync/sync-client";

type Recorded = { url: string; method: string; headers: Record<string, string>; body?: string };

type Reply = {
  status?: number;
  body?: string;
  error?: string;
};

/** Builds a client whose transport records requests and replies from `respond`. */
function makeClient(respond: (request: Recorded) => Reply, options: Partial<SyncClientOptions> = {}) {
  const recorded: Recorded[] = [];
  const client = new SyncClient({
    persist: false,
    syncKey: "C8-TEST-TEST-TEST",
    fetchImpl: async (request) => {
      const entry: Recorded = {
        url: request.url,
        method: request.method ?? "GET",
        headers: request.headers ?? {},
        body: request.body,
      };
      recorded.push(entry);
      const reply = respond(entry);
      const status = reply.status ?? 200;
      return {
        ok: status >= 200 && status < 300,
        status,
        headers: {},
        body: reply.body ?? "",
        error: reply.error,
      };
    },
    ...options,
  });
  return { client, recorded };
}

afterEach(() => {
  delete (globalThis as unknown as { chrome?: unknown }).chrome;
});

describe("API root normalisation", () => {
  it("accepts server.json's full sync URL and plain origins", () => {
    expect(normalizeApiRoot("https://comics8.lazylabo.com/api/comics8/sync")).toBe(
      "https://comics8.lazylabo.com/api/comics8",
    );
    expect(normalizeApiRoot("https://comics8.lazylabo.com/api/comics8/")).toBe(
      "https://comics8.lazylabo.com/api/comics8",
    );
    expect(normalizeApiRoot("https://h/api")).toBe("https://h/api");
    expect(normalizeApiRoot("https://h/api/sync/delta")).toBe("https://h/api/sync/delta");
  });

  it("matches the native default server URL", () => {
    expect(DEFAULT_SERVER_URL).toBe("https://comics8.lazylabo.com/api/comics8/sync");
    expect(normalizeApiRoot(DEFAULT_SERVER_URL)).toBe("https://comics8.lazylabo.com/api/comics8");
  });

  it("strips endpoint suffixes used by SyncConstants", () => {
    expect(normalizeApiRoot("https://h/pair/request")).toBe("https://h");
    expect(normalizeApiRoot("https://h/proxy")).toBe("https://h");
    expect(normalizeApiRoot("https://h/version")).toBe("https://h");
    expect(normalizeApiRoot("https://h/health")).toBe("https://h");
  });
});

describe("sync keys", () => {
  it("generates C8-XXXX-XXXX-XXXX over the documented alphabet", () => {
    for (let i = 0; i < 25; i += 1) {
      const key = generateSyncKey();
      expect(key).toMatch(/^C8-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}$/);
    }
    expect(new Set(Array.from({ length: 20 }, generateSyncKey)).size).toBe(20);
  });
});

describe("push", () => {
  const updates = {
    favorites: [
      {
        id: "toon-1",
        sourceId: "peppercarrot",
        title: "Pepper & Carrot",
        thumbUrl: "https://x/cover.jpg",
        href: "https://x/series",
        savedAt: 1_700_000_000_000,
      },
    ],
    history: [
      {
        toonId: "toon-1",
        sourceId: "peppercarrot",
        toonTitle: "Pepper & Carrot",
        lastWrId: "ep01",
        lastReadOrder: 1,
        totalEpisodes: 39,
        lastReadAt: 1_700_000_000_500,
      },
    ],
    readEpisodes: [{ toonId: "toon-1", sourceId: "peppercarrot", wrId: "ep01", readAt: 1, lastPage: 7 }],
    readerSettings: [
      {
        toonId: "toon-1",
        sourceId: "peppercarrot",
        viewMode: "DOUBLE",
        readDirection: "LEFT_TO_RIGHT",
        splitMode: "FIT",
        updatedAt: 1_700_000_001_000,
      },
    ],
  };

  it("POSTs the four buckets to /sync with both auth headers", async () => {
    const { client, recorded } = makeClient(() => ({
      body: JSON.stringify({
        status: "success",
        savedAt: 1_700_000_002_000,
        userId: "C8TESTTESTTEST",
        sizeBytes: 512,
        favorites: 1,
        history: 1,
        isPro: false,
      }),
    }));

    const result = await client.push(updates);

    expect(recorded).toHaveLength(1);
    expect(recorded[0].url).toBe("https://comics8.lazylabo.com/api/comics8/sync");
    expect(recorded[0].method).toBe("POST");
    // `get_sync_user` accepts either header; the native clients send both.
    expect(recorded[0].headers.Authorization).toBe("Bearer C8-TEST-TEST-TEST");
    expect(recorded[0].headers["X-Sync-Key"]).toBe("C8-TEST-TEST-TEST");
    expect(recorded[0].headers["Content-Type"]).toContain("application/json");

    const payload = JSON.parse(recorded[0].body ?? "{}");
    expect(Object.keys(payload).sort()).toEqual(
      ["favorites", "history", "readEpisodes", "readerSettings"].sort(),
    );
    expect(payload.favorites[0].id).toBe("toon-1");
    expect(payload.history[0].lastReadOrder).toBe(1);
    expect(payload.readEpisodes[0].lastPage).toBe(7);
    expect(payload.readerSettings[0].viewMode).toBe("DOUBLE");

    expect(result).toMatchObject({
      status: "success",
      savedAt: 1_700_000_002_000,
      favorites: 1,
      history: 1,
      isPro: false,
    });
    expect(client.getStatus()).toMatchObject({
      lastSyncedAt: 1_700_000_002_000,
      lastResult: "success",
      lastError: null,
    });
  });

  it("rejects payloads beyond the server item limit without calling the network", async () => {
    const { client, recorded } = makeClient(() => ({ body: "{}" }));
    const huge = {
      ...emptyUpdates(),
      readEpisodes: Array.from({ length: MAX_SYNC_ITEMS_PER_REQUEST + 1 }, (_, i) => ({
        toonId: `t${i}`,
        wrId: `w${i}`,
      })),
    };
    await expect(client.push(huge)).rejects.toThrow(/at most 10000/);
    expect(recorded).toHaveLength(0);
  });

  it("maps 401 to a SyncError with the server's message", async () => {
    const { client } = makeClient(() => ({
      status: 401,
      body: JSON.stringify({ detail: { error: "Unauthorized", message: "Invalid sync key" } }),
    }));

    await expect(client.push(emptyUpdates())).rejects.toMatchObject({
      name: "SyncError",
      status: 401,
      code: "Unauthorized",
      message: "Invalid sync key",
    });
    expect(client.getStatus()).toMatchObject({ lastResult: "error", lastError: "Invalid sync key" });
  });

  it("surfaces a bare detail string (FastAPI HTTPException(detail='...'))", async () => {
    const { client } = makeClient(() => ({
      status: 413,
      body: JSON.stringify({ detail: "Sync request is too large" }),
    }));
    await expect(client.push(emptyUpdates())).rejects.toThrow("Sync request is too large");
  });

  it("treats a non-success status field as a failure", async () => {
    const { client } = makeClient(() => ({ body: JSON.stringify({ status: "error" }) }));
    await expect(client.push(emptyUpdates())).rejects.toThrow(/not acknowledged/);
  });

  it("reports malformed JSON instead of silently succeeding", async () => {
    const { client } = makeClient(() => ({ body: "<html>proxy error</html>" }));
    await expect(client.push(emptyUpdates())).rejects.toThrow(/malformed JSON/);
  });

  it("reports transport failures", async () => {
    const { client } = makeClient(() => ({ error: "net::ERR_INTERNET_DISCONNECTED" }));
    await expect(client.push(emptyUpdates())).rejects.toThrow(/ERR_INTERNET_DISCONNECTED/);
    expect(client.getStatus().lastResult).toBe("error");
  });
});

describe("pull", () => {
  it("GETs /sync and normalises the snapshot", async () => {
    const { client, recorded } = makeClient(() => ({
      body: JSON.stringify({
        version: 2,
        appName: "Comics8",
        exportedAt: 1_700_000_009_000,
        isPro: true,
        favorites: [{ id: "a", sourceId: "s", title: "A" }],
        history: [{ toonId: "a", sourceId: "s", lastWrId: "ep1" }],
        readEpisodes: [{ toonId: "a", wrId: "ep1" }],
        readerSettings: [{ toonId: "a", viewMode: "SINGLE" }],
        tombstones: [{ entityType: "FAVORITE", entityId: "s:a", deletedAt: 5 }],
        catalog: [{ sourceId: "s", toonId: "a", totalEpisodes: 39 }],
      }),
    }));

    const snapshot = await client.pull();

    expect(recorded[0].method).toBe("GET");
    expect(recorded[0].url).toBe("https://comics8.lazylabo.com/api/comics8/sync");
    expect(snapshot).toMatchObject({ version: 2, isPro: true, exportedAt: 1_700_000_009_000 });
    expect(snapshot.favorites).toHaveLength(1);
    expect(snapshot.tombstones).toHaveLength(1);
    expect(snapshot.catalog[0].totalEpisodes).toBe(39);
    expect(client.getStatus().isPro).toBe(true);
  });

  it("defaults absent buckets to empty arrays", async () => {
    const { client } = makeClient(() => ({ body: JSON.stringify({ version: 2 }) }));
    const snapshot = await client.pull();
    expect(snapshot.favorites).toEqual([]);
    expect(snapshot.readEpisodes).toEqual([]);
    expect(snapshot.tombstones).toEqual([]);
  });
});

describe("catalog batch", () => {
  it("POSTs { items } to /catalog/batch and parses the catalog rows", async () => {
    const { client, recorded } = makeClient(() => ({
      body: JSON.stringify({
        catalog: [
          {
            sourceId: "s",
            toonId: "a",
            totalEpisodes: 64,
            updatedAt: 1_700_000_000_000,
            lastPage: 1,
            pageSize: 100,
            delta: 0,
          },
          { sourceId: "s", toonId: "b", totalEpisodes: 12, lastPage: 2, pageSize: 10, delta: -3 },
        ],
      }),
    }));

    const rows = await client.fetchCatalogBatch([
      { sourceId: "s", toonId: "a" },
      { sourceId: "s", toonId: "b" },
    ]);

    // Same URL as `SyncConstants.catalogBatchUrl` (the `/sync` suffix of the default is stripped).
    expect(recorded[0].url).toBe("https://comics8.lazylabo.com/api/comics8/catalog/batch");
    expect(recorded[0].method).toBe("POST");
    expect(recorded[0].headers["Content-Type"]).toContain("application/json");
    expect(recorded[0].headers["X-Sync-Key"]).toBe("C8-TEST-TEST-TEST");
    expect(JSON.parse(recorded[0].body ?? "{}")).toEqual({
      items: [
        { sourceId: "s", toonId: "a" },
        { sourceId: "s", toonId: "b" },
      ],
    });
    expect(rows.map((row) => [row.toonId, row.totalEpisodes])).toEqual([
      ["a", 64],
      ["b", 12],
    ]);
  });

  it("caps the batch at the server's 200-row window", async () => {
    const { client, recorded } = makeClient(() => ({ body: JSON.stringify({ catalog: [] }) }));
    const items = Array.from({ length: 250 }, (_, index) => ({
      sourceId: "s",
      toonId: String(index),
    }));

    await client.fetchCatalogBatch(items);

    const body = JSON.parse(recorded[0].body ?? "{}");
    expect(body.items).toHaveLength(MAX_CATALOG_BATCH_ITEMS);
    expect(body.items[0]).toEqual({ sourceId: "s", toonId: "0" });
  });

  it("answers [] without a request when nothing is asked", async () => {
    const { client, recorded } = makeClient(() => ({ body: JSON.stringify({ catalog: [] }) }));
    expect(await client.fetchCatalogBatch([])).toEqual([]);
    expect(await client.fetchCatalogBatch([{ sourceId: "s", toonId: "" }])).toEqual([]);
    expect(recorded).toHaveLength(0);
  });

  it("resolves the batch URL from a serverUrl that still carries /sync", async () => {
    // The dialog lets a user paste `server.json`'s URL verbatim; the root is what the route hangs off.
    const { client, recorded } = makeClient(() => ({ body: JSON.stringify({ catalog: [] }) }), {
      serverUrl: "https://comics8.lazylabo.com/api/comics8/sync",
    });
    await client.fetchCatalogBatch([{ sourceId: "s", toonId: "a" }]);
    expect(recorded[0].url).toBe("https://comics8.lazylabo.com/api/comics8/catalog/batch");
  });

  it("answers [] on transport, HTTP and JSON failures", async () => {
    const transport = makeClient(() => ({ error: "boom" }));
    expect(await transport.client.fetchCatalogBatch([{ sourceId: "s", toonId: "a" }])).toEqual(
      [],
    );

    const limited = makeClient(() => ({ status: 429, body: "" }));
    expect(await limited.client.fetchCatalogBatch([{ sourceId: "s", toonId: "a" }])).toEqual([]);

    const malformed = makeClient(() => ({ body: "not json" }));
    expect(await malformed.client.fetchCatalogBatch([{ sourceId: "s", toonId: "a" }])).toEqual([]);

    const missing = makeClient(() => ({ body: JSON.stringify({}) }));
    expect(await missing.client.fetchCatalogBatch([{ sourceId: "s", toonId: "a" }])).toEqual([]);
  });

  it("drops rows that do not identify a work", async () => {
    const { client } = makeClient(() => ({
      body: JSON.stringify({
        catalog: [
          { sourceId: "s", toonId: "a", totalEpisodes: 5 },
          { sourceId: "", toonId: "b", totalEpisodes: 5 },
          { sourceId: "s", toonId: "", totalEpisodes: 5 },
          null,
        ],
      }),
    }));
    const rows = await client.fetchCatalogBatch([{ sourceId: "s", toonId: "a" }]);
    expect(rows.map((row) => row.toonId)).toEqual(["a"]);
  });
});

describe("delta", () => {
  it("POSTs since/clientTime/version and applies the returned changes", async () => {
    const { client, recorded } = makeClient((request) => {
      if (request.url.endsWith("/sync/delta")) {
        return {
          body: JSON.stringify({
            status: "success",
            serverTime: 1_700_000_100_000,
            userId: "u",
            isPro: false,
            changes: {
              favorites: [{ id: "b", sourceId: "s" }],
              history: [],
              readEpisodes: [{ toonId: "b", wrId: "e" }],
              readerSettings: [],
              tombstones: [{ entityType: "FAVORITE", entityId: "s:c", deletedAt: 1 }],
              catalog: [],
            },
          }),
        };
      }
      return { body: JSON.stringify({}) };
    });

    const result = await client.delta({ favorites: [{ id: "a" }] });

    const body = JSON.parse(recorded[0].body ?? "{}");
    expect(recorded[0].url).toBe("https://comics8.lazylabo.com/api/comics8/sync/delta");
    expect(body.version).toBe(2);
    expect(body.since).toBe(0);
    expect(typeof body.clientTime).toBe("number");
    expect(body.changes.favorites).toEqual([{ id: "a" }]);
    expect(body.changes.readEpisodes).toEqual([]);

    expect(result.serverTime).toBe(1_700_000_100_000);
    expect(result.changes.favorites).toHaveLength(1);
    expect(result.changes.tombstones).toHaveLength(1);
    expect(client.getStatus().lastSyncedAt).toBe(1_700_000_100_000);
  });

  it("uses the last successful sync time as `since` by default", async () => {
    const { client, recorded } = makeClient((request) =>
      request.url.endsWith("/sync")
        ? { body: JSON.stringify({ exportedAt: 1_700_000_500_000 }) }
        : { body: JSON.stringify({ serverTime: 1_700_000_600_000 }) },
    );

    await client.pull();
    await client.delta();

    const deltaBody = JSON.parse(recorded[1].body ?? "{}");
    expect(deltaBody.since).toBe(1_700_000_500_000);
  });
});

describe("pairing", () => {
  it("pushes the snapshot first so the account exists, then requests a PIN", async () => {
    const { client, recorded } = makeClient((request) => {
      if (request.url.endsWith("/pair/request")) {
        return { body: JSON.stringify({ success: true, code: "482913", expiresInSeconds: 300 }) };
      }
      return { body: JSON.stringify({ status: "success", savedAt: 1, favorites: 0, history: 0 }) };
    });

    const result = await client.requestPairingCode();

    expect(recorded.map((r) => r.url)).toEqual([
      "https://comics8.lazylabo.com/api/comics8/sync",
      "https://comics8.lazylabo.com/api/comics8/pair/request",
    ]);
    expect(recorded[1].method).toBe("POST");
    expect(recorded[1].body).toBe("{}");
    expect(recorded[1].headers.Authorization).toBe("Bearer C8-TEST-TEST-TEST");
    expect(result).toEqual({ success: true, code: "482913", expiresInSeconds: 300 });
  });

  it("can skip the pre-push when the account already exists", async () => {
    const { client, recorded } = makeClient(() => ({
      body: JSON.stringify({ success: true, code: "111111" }),
    }));
    await client.requestPairingCode({ pushBeforePairing: false });
    expect(recorded.map((r) => r.url)).toEqual(["https://comics8.lazylabo.com/api/comics8/pair/request"]);
  });

  it("still requests a PIN when the pre-push fails for a non-auth reason", async () => {
    const { client, recorded } = makeClient((request) =>
      request.url.endsWith("/pair/request")
        ? { body: JSON.stringify({ success: true, code: "222222", expiresInSeconds: 300 }) }
        : { status: 500, body: "boom" },
    );
    const result = await client.requestPairingCode();
    expect(result.code).toBe("222222");
    expect(recorded).toHaveLength(2);
  });

  it("propagates 401 from the pre-push, since pairing needs a known account", async () => {
    const { client } = makeClient(() => ({
      status: 401,
      body: JSON.stringify({ detail: { message: "Sync account has no stored data" } }),
    }));
    await expect(client.requestPairingCode()).rejects.toThrow("Sync account has no stored data");
  });

  it("rejects a pairing response without a code", async () => {
    const { client } = makeClient((request) =>
      request.url.endsWith("/pair/request")
        ? { body: JSON.stringify({ success: true }) }
        : { body: JSON.stringify({ status: "success" }) },
    );
    await expect(client.requestPairingCode()).rejects.toThrow(/did not return a pairing code/);
  });

  it("normalises the PIN before confirming and stores the returned key", async () => {
    const { client, recorded } = makeClient(() => ({
      body: JSON.stringify({ success: true, syncKey: "C8-NEWK-NEWK-NEWK" }),
    }));

    const result = await client.confirmPairingCode(" 48-29 13 ");

    expect(recorded[0].url).toBe("https://comics8.lazylabo.com/api/comics8/pair/confirm");
    expect(JSON.parse(recorded[0].body ?? "{}")).toEqual({ code: "482913" });
    // /pair/confirm authenticates with the code alone.
    expect(recorded[0].headers.Authorization).toBeUndefined();
    expect(result.syncKey).toBe("C8-NEWK-NEWK-NEWK");
    expect(client.syncKey).toBe("C8-NEWK-NEWK-NEWK");
    expect(client.getStatus().lastSyncedAt).toBe(0);
  });

  it("rejects a malformed PIN without a request", async () => {
    const { client, recorded } = makeClient(() => ({ body: "{}" }));
    await expect(client.confirmPairingCode("12345")).rejects.toThrow(/6-digit/);
    await expect(client.confirmPairingCode("abcdef")).rejects.toThrow(/6-digit/);
    expect(recorded).toHaveLength(0);
  });

  it("pair() chains request and confirm", async () => {
    const { client, recorded } = makeClient((request) => {
      if (request.url.endsWith("/pair/request")) {
        return { body: JSON.stringify({ success: true, code: "777777", expiresInSeconds: 300 }) };
      }
      if (request.url.endsWith("/pair/confirm")) {
        return { body: JSON.stringify({ success: true, syncKey: "C8-PAIR-PAIR-PAIR" }) };
      }
      return { body: JSON.stringify({ status: "success", savedAt: 1 }) };
    });

    const result = await client.pair();
    expect(result).toEqual({ code: "777777", expiresInSeconds: 300, syncKey: "C8-PAIR-PAIR-PAIR" });
    expect(recorded.map((r) => r.url)).toEqual([
      "https://comics8.lazylabo.com/api/comics8/sync",
      "https://comics8.lazylabo.com/api/comics8/pair/request",
      "https://comics8.lazylabo.com/api/comics8/pair/confirm",
    ]);
  });

  it("maps an expired PIN (400) to the server message", async () => {
    const { client } = makeClient(() => ({
      status: 400,
      body: JSON.stringify({
        detail: { success: false, message: "유효하지 않거나 만료된 페어링 코드입니다." },
      }),
    }));
    await expect(client.confirmPairingCode("000000")).rejects.toThrow(/만료된 페어링 코드/);
  });

  it("maps the 429 pairing rate limit", async () => {
    const { client } = makeClient(() => ({
      status: 429,
      body: JSON.stringify({ detail: { success: false, message: "페어링 시도가 너무 많습니다." } }),
    }));
    await expect(client.confirmPairingCode("123456")).rejects.toMatchObject({
      status: 429,
      code: "RateLimited",
    });
  });
});

describe("persistence and configuration", () => {
  it("mints and stores a key on first init", async () => {
    const store: Record<string, unknown> = {};
    (globalThis as unknown as { chrome: unknown }).chrome = {
      storage: {
        local: {
          get: async (key: string) => ({ [key]: store[key] }),
          set: async (values: Record<string, unknown>) => {
            Object.assign(store, values);
          },
        },
      },
    };

    const client = new SyncClient({ serverUrl: DEFAULT_SERVER_URL });
    const status = await client.init();
    expect(status.syncKey).toMatch(/^C8-/);
    expect(store[SYNC_STORAGE_KEY]).toMatchObject({ syncKey: status.syncKey });
  });

  it("reloads credentials written by a previous session", async () => {
    const saved = {
      syncKey: "C8-SAVD-SAVD-SAVD",
      serverUrl: "https://custom.test/api",
      lastSyncedAt: 123,
      lastResult: "success" as const,
      lastError: null,
      isPro: true,
      autoSyncEnabled: false,
    };
    (globalThis as unknown as { chrome: unknown }).chrome = {
      storage: {
        local: {
          get: async (key: string) => ({ [key]: key === SYNC_STORAGE_KEY ? saved : undefined }),
          set: async () => {},
        },
      },
    };

    const client = new SyncClient();
    const status = await client.init();
    expect(status).toMatchObject(saved);
  });

  it("resets lastSyncedAt when the server changes", async () => {
    const { client, recorded } = makeClient(
      () => ({ body: JSON.stringify({ exportedAt: 1_700_000_000_000 }) }),
      {},
    );
    await client.pull();
    expect(client.getStatus().lastSyncedAt).toBe(1_700_000_000_000);

    await client.setServerUrl("https://other.test/api/sync");
    expect(client.getStatus().lastSyncedAt).toBe(0);
    expect(client.serverUrl).toBe("https://other.test/api");

    await client.pull();
    expect(recorded.at(-1)?.url).toBe("https://other.test/api/sync");
  });

  it("rejects a non-http server URL", async () => {
    const { client } = makeClient(() => ({ body: "{}" }));
    await expect(client.setServerUrl("ftp://x")).rejects.toThrow(/must start with http/);
  });

  it("validates sync keys", async () => {
    const { client } = makeClient(() => ({ body: "{}" }));
    await expect(client.setSyncKey("  ")).rejects.toThrow(/must not be empty/);
    await client.setSyncKey("C8-AB12-CD34-EF56");
    expect(client.syncKey).toBe("C8-AB12-CD34-EF56");
  });

  it("tracks the auto-sync flag", async () => {
    const { client } = makeClient(() => ({ body: "{}" }));
    expect(client.getStatus().autoSyncEnabled).toBe(EMPTY_STATUS.autoSyncEnabled);
    await client.setAutoSyncEnabled(false);
    expect(client.getStatus().autoSyncEnabled).toBe(false);
  });

  it("works without chrome.storage (service-worker startup, tests)", async () => {
    const client = new SyncClient({ persist: false, syncKey: "C8-NONE-NONE-NONE" });
    await client.init();
    expect(client.syncKey).toBe("C8-NONE-NONE-NONE");
  });

  it("is a no-op when the sync key is missing at request time", async () => {
    const client = new SyncClient({
      persist: false,
      syncKey: "",
      fetchImpl: async () => ({ ok: true, status: 200, headers: {}, body: "{}" }),
    });
    await client.init();
    // init mints a key rather than sending an empty Authorization header.
    expect(client.syncKey).toMatch(/^C8-/);
  });
});

describe("syncNow", () => {
  it("pushes then pulls the merged snapshot", async () => {
    const { client, recorded } = makeClient((request) => {
      if (request.method === "POST") {
        return { body: JSON.stringify({ status: "success", savedAt: 10, favorites: 1, history: 1 }) };
      }
      return {
        body: JSON.stringify({
          exportedAt: 20,
          favorites: [{ id: "a", sourceId: "s" }],
          history: [{ toonId: "a", sourceId: "s" }],
        }),
      };
    });

    const { pushed, pulled } = await client.syncNow({
      favorites: [{ id: "a", sourceId: "s" }],
      history: [{ toonId: "a", sourceId: "s" }],
      readEpisodes: [],
      readerSettings: [],
    });

    expect(recorded.map((r) => r.method)).toEqual(["POST", "GET"]);
    expect(pushed.savedAt).toBe(10);
    expect(pulled?.favorites).toHaveLength(1);
    expect(client.getStatus().lastSyncedAt).toBe(20);
  });

  it("can skip the pull", async () => {
    const { client, recorded } = makeClient(() => ({
      body: JSON.stringify({ status: "success", savedAt: 10 }),
    }));
    const { pulled } = await client.syncNow(emptyUpdates(), { pullBack: false });
    expect(pulled).toBeUndefined();
    expect(recorded).toHaveLength(1);
  });

  it("exposes bucket counts used by the reader", async () => {
    const { client } = makeClient(() => ({
      body: JSON.stringify({ status: "success", savedAt: 1, favorites: 2, history: 3, readEpisodes: 4 }),
    }));
    const result = await client.push({
      favorites: [{ id: "a" }, { id: "b" }],
      history: [{ toonId: "a" }, { toonId: "b" }, { toonId: "c" }],
      readEpisodes: [{ toonId: "a", wrId: "1" }],
      readerSettings: [],
    });
    expect(result).toMatchObject({ favorites: 2, history: 3, readEpisodes: 4 });
  });
});

describe("SyncError", () => {
  it("carries status and code", () => {
    const error = new SyncError("boom", 429, "RateLimited");
    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe("SyncError");
    expect(error.status).toBe(429);
    expect(error.code).toBe("RateLimited");
  });
});
