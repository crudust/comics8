/**
 * Source registry: the (empty) built-in list plus user-installed scripts.
 *
 * The extension ships no comic source: {@link BUILTIN_SOURCES} is empty and every web-comic source
 * is a `.js` plugin the user installed, so every one of them can be uninstalled again.
 *
 * Installed scripts are persisted in `chrome.storage.local` under {@link INSTALLED_SOURCES_KEY} and
 * compiled lazily, so a large plugin library costs nothing until a source is actually opened.
 */

import { BUILTIN_SOURCES } from "./builtin";
import { loadSource, type LoadedSource, type PluginHostOptions } from "./plugin-host";
import { isExtensionContext, sandboxGetMeta, SandboxLoadedSource } from "./sandbox-bridge";
import type { BuiltinSource, SourceCatalog } from "./types";

export const INSTALLED_SOURCES_KEY = "comics8.sources.installed";

/** Summary shown in the source picker; safe to send to the UI. */
export type SourceSummary = {
  id: string;
  displayName: string;
  origin: string;
  catalogs: SourceCatalog[];
  builtin: boolean;
};

type InstalledSourceRecord = {
  id: string;
  displayName?: string;
  origin?: string;
  catalogs?: SourceCatalog[];
  script: string;
  updatedAt: number;
};

export type AnyLoadedSource = LoadedSource | SandboxLoadedSource;

type RegistryEntry =
  | { kind: "builtin"; builtin: BuiltinSource; loaded?: AnyLoadedSource }
  | {
      kind: "installed";
      id: string;
      displayName: string;
      origin: string;
      catalogs: SourceCatalog[];
      script: string;
      updatedAt: number;
      loaded?: AnyLoadedSource;
    };

export type SourceRegistryOptions = {
  /** Extra built-ins beyond {@link BUILTIN_SOURCES} (mainly for tests). */
  builtins?: readonly BuiltinSource[];
  /** Overrides passed to every {@link loadSource} call. */
  hostOptions?: PluginHostOptions;
  /** Skips `chrome.storage.local` (tests / non-extension contexts). */
  persist?: boolean;
};

export class SourceRegistry {
  private readonly entries = new Map<string, RegistryEntry>();
  private readonly builtins: readonly BuiltinSource[];
  private readonly hostOptions: PluginHostOptions;
  private readonly persist: boolean;

  constructor(options: SourceRegistryOptions = {}) {
    this.builtins = options.builtins ?? BUILTIN_SOURCES;
    this.hostOptions = options.hostOptions ?? {};
    this.persist = options.persist ?? true;
    for (const builtin of this.builtins) {
      this.entries.set(builtin.id, { kind: "builtin", builtin });
    }
  }

  /** Hydrates installed scripts from `chrome.storage.local`. */
  async init(): Promise<void> {
    const records = await this.readInstalled();
    for (const record of records) {
      if (this.entries.has(record.id)) continue;
      this.entries.set(record.id, {
        kind: "installed",
        id: record.id,
        displayName: record.displayName || record.id,
        origin: record.origin || "",
        catalogs: record.catalogs || [],
        script: record.script,
        updatedAt: record.updatedAt,
      });
    }
  }

  /** Lists sources without compiling any plugin script. */
  list(): SourceSummary[] {
    return Array.from(this.entries.values()).map((entry) => {
      if (entry.kind === "builtin") {
        if (entry.loaded) {
          return {
            id: entry.loaded.id,
            displayName: entry.loaded.displayName,
            origin: entry.loaded.meta.origin,
            catalogs: entry.loaded.catalogs,
            builtin: true,
          };
        }
        return {
          id: entry.builtin.id,
          displayName: entry.builtin.displayName,
          origin: "",
          catalogs: [] as SourceCatalog[],
          builtin: true,
        };
      }
      return {
        id: entry.loaded?.id ?? entry.id,
        displayName: entry.loaded?.displayName ?? entry.displayName,
        origin: entry.loaded?.meta.origin ?? entry.origin,
        catalogs: entry.loaded?.catalogs ?? entry.catalogs,
        builtin: false,
      };
    });
  }

  /**
   * Compiles (once) and returns the source, or `null` when the id is unknown.
   *
   * Compilation errors are surfaced here, at first use, rather than at install time: a plugin that
   * fails to compile must not take the whole registry down.
   */
  async get(sourceId: string): Promise<AnyLoadedSource | null> {
    const entry = this.entries.get(sourceId);
    if (!entry) return null;
    if (entry.loaded) return entry.loaded;

    const script = entry.kind === "builtin" ? entry.builtin.script : entry.script;

    if (isExtensionContext()) {
      const metaResult = await sandboxGetMeta(script);
      const loaded = new SandboxLoadedSource(script, metaResult.meta, metaResult.catalogs);
      entry.loaded = loaded;
      return loaded;
    }

    const loaded = loadSource(script, { ...this.hostOptions, origin: entry.kind === "builtin" ? entry.builtin.id : undefined });
    entry.loaded = loaded;
    return loaded;
  }

  /** Compiles a script first, then stores it. A broken plugin never lands in storage. */
  async install(script: string, options: { replace?: boolean } = {}): Promise<SourceSummary> {
    if (isExtensionContext()) {
      const metaResult = await sandboxGetMeta(script);
      const existing = this.entries.get(metaResult.meta.id);
      if (existing && !options.replace) {
        throw new Error(`source "${metaResult.meta.id}" is already installed`);
      }

      const loaded = new SandboxLoadedSource(script, metaResult.meta, metaResult.catalogs);
      this.entries.set(metaResult.meta.id, {
        kind: "installed",
        id: metaResult.meta.id,
        displayName: metaResult.meta.displayName,
        origin: metaResult.meta.origin,
        catalogs: metaResult.catalogs,
        script,
        updatedAt: Date.now(),
        loaded,
      });
      await this.persistInstalled();
      return {
        id: metaResult.meta.id,
        displayName: metaResult.meta.displayName,
        origin: metaResult.meta.origin,
        catalogs: metaResult.catalogs,
        builtin: false,
      };
    }

    const loaded = loadSource(script, this.hostOptions);
    const existing = this.entries.get(loaded.id);
    if (existing && !options.replace) {
      throw new Error(`source "${loaded.id}" is already installed`);
    }

    this.entries.set(loaded.id, {
      kind: "installed",
      id: loaded.id,
      displayName: loaded.displayName,
      origin: loaded.meta.origin,
      catalogs: loaded.catalogs,
      script,
      updatedAt: Date.now(),
      loaded,
    });
    await this.persistInstalled();
    return {
      id: loaded.id,
      displayName: loaded.displayName,
      origin: loaded.meta.origin,
      catalogs: loaded.catalogs,
      builtin: false,
    };
  }

  /** Removes an installed source. Built-ins (if a caller registered any) cannot be removed. */
  async uninstall(sourceId: string): Promise<boolean> {
    const entry = this.entries.get(sourceId);
    if (!entry || entry.kind === "builtin") return false;
    this.entries.delete(sourceId);
    await this.persistInstalled();
    return true;
  }

  /** Raw script for an installed source (used by "export source" UI). */
  scriptFor(sourceId: string): string | null {
    const entry = this.entries.get(sourceId);
    if (!entry) return null;
    return entry.kind === "builtin" ? entry.builtin.script : entry.script;
  }

  // -----------------------------------------------------------------------------------------------
  // persistence
  // -----------------------------------------------------------------------------------------------

  private async readInstalled(): Promise<InstalledSourceRecord[]> {
    if (!this.persist) return [];
    const area = chromeStorageArea();
    if (!area) return [];
    try {
      const stored = await area.get(INSTALLED_SOURCES_KEY);
      return parseInstalled(stored?.[INSTALLED_SOURCES_KEY]);
    } catch {
      return [];
    }
  }

  private async persistInstalled(): Promise<void> {
    if (!this.persist) return;
    const area = chromeStorageArea();
    if (!area) return;
    const records: InstalledSourceRecord[] = [];
    for (const [id, entry] of this.entries) {
      if (entry.kind !== "installed") continue;
      records.push({
        id,
        displayName: entry.displayName,
        origin: entry.origin,
        catalogs: entry.catalogs,
        script: entry.script,
        updatedAt: entry.updatedAt,
      });
    }
    await area.set({ [INSTALLED_SOURCES_KEY]: records });
  }
}

function chromeStorageArea(): chrome.storage.StorageArea | null {
  if (typeof chrome === "undefined" || !chrome.storage?.local) return null;
  return chrome.storage.local;
}

function parseInstalled(value: unknown): InstalledSourceRecord[] {
  if (!Array.isArray(value)) return [];
  const out: InstalledSourceRecord[] = [];
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) continue;
    const record = entry as Partial<InstalledSourceRecord>;
    if (typeof record.id !== "string" || typeof record.script !== "string") continue;
    out.push({
      id: record.id,
      displayName: typeof record.displayName === "string" ? record.displayName : record.id,
      origin: typeof record.origin === "string" ? record.origin : "",
      catalogs: Array.isArray(record.catalogs) ? record.catalogs : [],
      script: record.script,
      updatedAt: typeof record.updatedAt === "number" ? record.updatedAt : 0,
    });
  }
  return out;
}

export type { LoadedSource };
