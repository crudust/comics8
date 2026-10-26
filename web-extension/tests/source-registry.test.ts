/**
 * Source registry: built-ins, install/uninstall, lazy compilation, persistence.
 */

import { afterEach, describe, expect, it } from "vitest";
import { BUILTIN_SOURCES } from "../src/sources/builtin";
import { INSTALLED_SOURCES_KEY, SourceRegistry } from "../src/sources/registry";
import type { BuiltinSource } from "../src/sources/types";
import "./harness";

const noopFetch = async () => ({ ok: true, status: 200, headers: {}, body: "" });

const SCRIPT = (id: string, displayName = id): string => `var source = {
  id: ${JSON.stringify(id)},
  displayName: ${JSON.stringify(displayName)},
  origin: "https://${id}.test",
  catalogs: [{ id: "LATEST", label: "Latest", paginated: true }],
  loadListing: function(){
    return { items: [{ id: "1", title: "t", thumbUrl: "", href: "" }], pageInfo: { currentPage: 1, lastPage: 1 } };
  },
  loadEpisodes: function(){
    return { items: [{ wrId: "1", title: "e", href: "" }], pageInfo: { currentPage: 1, lastPage: 1 } };
  },
  resolveImages: function(){ return ["https://${id}.test/1.jpg"]; }
};`;

/** In-memory `chrome.storage.local`. */
function stubStorage(initial: Record<string, unknown> = {}): Record<string, unknown> {
  const store: Record<string, unknown> = { ...initial };
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
  return store;
}

afterEach(() => {
  delete (globalThis as unknown as { chrome?: unknown }).chrome;
});

describe("built-in sources", () => {
  it("ships no comic source at all", () => {
    expect(BUILTIN_SOURCES).toEqual([]);
  });

  // The Pepper & Carrot bundle stays in the repo as the reference plugin fixture for
  // `tests/plugin-host.test.ts`; `pnpm sync:builtin:check` guards it against drift.
});

describe("SourceRegistry", () => {
  it("lists nothing before a plugin is installed", () => {
    const registry = new SourceRegistry({ persist: false });
    expect(registry.list()).toEqual([]);
  });

  it("returns null for unknown ids", async () => {
    const registry = new SourceRegistry({ persist: false });
    await expect(registry.get("nope")).resolves.toBeNull();
  });

  it("refuses to add a built-in", async () => {
    const peppercarrot: BuiltinSource = {
      id: "peppercarrot",
      displayName: "Pepper & Carrot",
      script: SCRIPT("peppercarrot"),
    };
    const registry = new SourceRegistry({
      persist: false,
      builtins: [peppercarrot],
      hostOptions: { fetchImpl: noopFetch },
    });
    await expect(registry.install(SCRIPT("peppercarrot"))).rejects.toThrow(/already installed/);
  });

  it("installs, compiles and serves a plugin", async () => {
    const registry = new SourceRegistry({ persist: false, hostOptions: { fetchImpl: noopFetch } });
    const summary = await registry.install(SCRIPT("example", "Example Source"));

    expect(summary).toMatchObject({
      id: "example",
      displayName: "Example Source",
      origin: "https://example.test",
      builtin: false,
    });

    const source = await registry.get("example");
    expect(source?.displayName).toBe("Example Source");
    expect(registry.list().map((entry) => entry.id)).toEqual(["example"]);
  });

  it("rejects a broken plugin without storing it", async () => {
    const registry = new SourceRegistry({ persist: false, hostOptions: { fetchImpl: noopFetch } });
    await expect(registry.install("var nope = 1;")).rejects.toThrow(/top-level 'source'/);
    await expect(registry.get("nope")).resolves.toBeNull();
    expect(registry.list()).toEqual([]);
  });

  it("requires an explicit replace to overwrite", async () => {
    const registry = new SourceRegistry({ persist: false, hostOptions: { fetchImpl: noopFetch } });
    await registry.install(SCRIPT("example", "First"));
    await expect(registry.install(SCRIPT("example", "Second"))).rejects.toThrow(/already installed/);

    const updated = await registry.install(SCRIPT("example", "Second"), { replace: true });
    expect(updated.displayName).toBe("Second");
    expect((await registry.get("example"))?.displayName).toBe("Second");
  });

  it("uninstalls every installed source and refuses unknown ids", async () => {
    const registry = new SourceRegistry({ persist: false, hostOptions: { fetchImpl: noopFetch } });
    await registry.install(SCRIPT("example"));

    await expect(registry.uninstall("peppercarrot")).resolves.toBe(false);
    await expect(registry.uninstall("example")).resolves.toBe(true);
    await expect(registry.uninstall("example")).resolves.toBe(false);
    await expect(registry.get("example")).resolves.toBeNull();
    expect(registry.list()).toEqual([]);
  });

  it("exposes raw scripts for export", async () => {
    const registry = new SourceRegistry({ persist: false, hostOptions: { fetchImpl: noopFetch } });
    await registry.install(SCRIPT("example"));
    expect(registry.scriptFor("example")).toContain('id: "example"');
    expect(registry.scriptFor("missing")).toBeNull();
  });

  it("persists installed scripts and reloads them in a new registry", async () => {
    const store = stubStorage();
    const registry = new SourceRegistry({ hostOptions: { fetchImpl: noopFetch } });
    await registry.init();
    await registry.install(SCRIPT("example", "Example"));

    const saved = store[INSTALLED_SOURCES_KEY] as Array<{ id: string; script: string }>;
    expect(saved.map((entry) => entry.id)).toEqual(["example"]);

    const reloaded = new SourceRegistry({ hostOptions: { fetchImpl: noopFetch } });
    await reloaded.init();
    expect((await reloaded.get("example"))?.displayName).toBe("Example");
  });

  it("ignores malformed persisted entries instead of failing to start", async () => {
    stubStorage({ [INSTALLED_SOURCES_KEY]: [null, 42, { id: "noScript" }, { script: "x" }] });
    const registry = new SourceRegistry({ hostOptions: { fetchImpl: noopFetch } });
    await registry.init();
    expect(registry.list()).toEqual([]);
  });

  it("survives a storage read failure", async () => {
    (globalThis as unknown as { chrome: unknown }).chrome = {
      storage: {
        local: {
          get: async () => {
            throw new Error("storage unavailable");
          },
          set: async () => {},
        },
      },
    };
    const registry = new SourceRegistry({ hostOptions: { fetchImpl: noopFetch } });
    await expect(registry.init()).resolves.toBeUndefined();
    expect(registry.list()).toEqual([]);
  });

  it("works with no chrome.storage at all", async () => {
    const registry = new SourceRegistry({ hostOptions: { fetchImpl: noopFetch } });
    await expect(registry.init()).resolves.toBeUndefined();
    await expect(registry.install(SCRIPT("example"))).resolves.toMatchObject({ id: "example" });
  });

  it("accepts extra built-ins (used to test alternative plugin shapes)", async () => {
    const extra: BuiltinSource = { id: "extra", displayName: "Extra", script: SCRIPT("extra") };
    const registry = new SourceRegistry({
      persist: false,
      builtins: [...BUILTIN_SOURCES, extra],
      hostOptions: { fetchImpl: noopFetch },
    });
    await expect(registry.get("extra")).resolves.toMatchObject({ id: "extra" });
    expect(registry.list().map((entry) => entry.id)).toEqual(["extra"]);
    // Even an explicitly registered built-in is never removable; it is the caller's, not the user's.
    await expect(registry.uninstall("extra")).resolves.toBe(false);
  });
});
