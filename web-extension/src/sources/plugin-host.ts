/**
 * `createPluginHost()` — the browser polyfill of the Comics8 source plugin environment.
 *
 * The native apps run plugins under Rhino with a synchronous `host` object (`docs/PLUGIN_GUIDE.md`
 * §1, `core/src/main/kotlin/com/comics8/core/source/js/HostApiV1.kt`). The browser cannot block, so
 * this polyfill keeps the *plugin-facing* contract byte-for-byte and gets the blocking behaviour from
 * {@link ReplayEngine}: a plugin method is re-executed until a pass needs no new network data. A
 * plugin that follows the spec runs unmodified — see `peppercarrot.js`, which is bundled and tested
 * verbatim.
 *
 * Only the outer entry points (`loadListing`, `loadEpisodes`, `resolveImages`, `search`, …) are
 * async; everything the plugin itself touches — `host.fetchText`, `host.parseHtml`,
 * `host.extractImages`, `host.parsePageInfo` — is synchronous, exactly like the native host.
 */

import { backgroundFetch } from "../background-fetch";
import { describeError } from "../messaging";
import {
  HostHtmlDoc,
  extractBackgroundUrl,
  parseHtmlDocument,
  resolveAbsUrl,
} from "./dom";
import {
  DEFAULT_CONCURRENCY,
  DEFAULT_MAX_PASSES,
  DEFAULT_MAX_REQUESTS,
  ReplayEngine,
  type ReplayRequest,
  type ReplayResponse,
} from "./replay";
import type {
  EpisodeItem,
  EpisodeResult,
  ListingResult,
  NotificationMode,
  PageInfo,
  ProgressDisplay,
  ReportedTotalFields,
  SearchQuery,
  SearchSuggestion,
  SourceCatalog,
  SourceMeta,
  ToonItem,
} from "./types";

export const HOST_API_LEVEL = 1;

/** §1: strict 30s execution limit per method invocation in the native engine. */
export const DEFAULT_CALL_TIMEOUT_MS = 30_000;

/** §6.1 `host.fetch`: transient statuses the native host retries. */
const RETRYABLE_STATUS = new Set([403, 408, 425, 429, 500, 502, 503, 504]);
const BACKOFF_MS = [250, 1_000, 4_000];

/** Every field a plugin may use to state the real episode count of a listing. */
const REPORTED_TOTAL_FIELDS = ["total", "totalItems", "itemCount", "totalEpisodes"] as const;

const IGNORE_IMAGE_PATTERNS = [
  "icon-",
  "favicon",
  "192x192",
  "/toonfile/toonres/",
  "logo",
  "banner",
  "avatar",
  "captcha",
];

const JS_IMG_ARRAY_RE =
  /(?:var|let|const)\s+(?:img_list|img_list_2|data_list|arr_img|images|toon_images)\s*=\s*\[([\s\S]*?)\]/gi;
const JS_ARRAY_STRING_RE = /["']([^"'\r\n]+)["']/g;

const DOM_CONTAINER_SELECTOR =
  "#scroll-list, .view-content, .scroll-viewer, #page-list, #contents-wrapper, #toon_view, .comic-view";

const IMAGE_EXTENSIONS = [".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif", ".bmp"];

/** Common named entities; the DOM path covers the full HTML5 table when '<' is absent. */
const NAMED_ENTITIES: Record<string, string> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: "\u00a0",
  hellip: "…",
  mdash: "—",
  ndash: "–",
  lsquo: "‘",
  rsquo: "’",
  ldquo: "“",
  rdquo: "”",
  middot: "·",
  times: "×",
  laquo: "«",
  raquo: "»",
  copy: "©",
  reg: "®",
  trade: "™",
  deg: "°",
};

export type FetchSpec = {
  url: string;
  method?: "GET" | "HEAD";
  headers?: Record<string, string>;
};

type TransportFn = (request: ReplayRequest) => Promise<ReplayResponse>;
function resolveMethod(method: FetchSpec["method"]): "GET" | "HEAD" {
  if (method === undefined) return "GET";
  const upper = `${method}`.toUpperCase();
  if (upper !== "GET" && upper !== "HEAD") {
    throw new Error("host.fetch method must be GET or HEAD");
  }
  return upper;
}

/** §6.1 `HostFetchResult`. */
export class HostFetchResult {
  readonly code: number;
  readonly body: Uint8Array;
  private readonly headers: Record<string, string>;

  constructor(code: number, headers: Record<string, string>, body: Uint8Array) {
    this.code = code;
    this.headers = headers;
    this.body = body;
  }

  /** Alias of {@link code}; `fetch`-style callers expect `status`. */
  get status(): number {
    return this.code;
  }

  header(name: string): string | null {
    const target = name.toLowerCase();
    for (const [key, value] of Object.entries(this.headers)) {
      if (key.toLowerCase() === target) return value;
    }
    return null;
  }

  totalLength(): number | null {
    const declared = this.header("content-length");
    if (declared) {
      const parsed = Number.parseInt(declared, 10);
      if (Number.isFinite(parsed) && parsed >= 0) return parsed;
    }
    const encoding = this.header("content-encoding")?.toLowerCase() ?? "";
    return encoding === "" || encoding === "identity" ? this.body.byteLength : null;
  }

  text(): string {
    return new TextDecoder("utf-8").decode(this.body);
  }

  json(): unknown {
    return JSON.parse(this.text());
  }
}

/** What the host returns for a blocked request; must be inert but shape-compatible. */
const INERT_FETCH_RESULT = new HostFetchResult(0, {}, new Uint8Array(0));
const INERT_CACHE_MISS = null;
const INERT_ACCESSIBLE = false;

export type PluginHostOptions = {
  /** Default headers for every request (`source.extraHeaders`, UA, Referer). */
  extraHeaders?: Record<string, string>;
  /** Plugin id, used to prefix `log()` output. */
  sourceId?: string;
  /** Active UI language, surfaced as `host.language`. */
  language?: string | null;
  /** Upper bound on host round trips per call. */
  maxRequests?: number;
  /** Dependency-chain depth per call; docs in `replay.ts`. */
  maxPasses?: number;
  /** Budget covering the async legs of a call (the native 30s limit). */
  callTimeoutMs?: number;
  /** Overrides the `FETCH_REQUEST` transport (tests, or in-page zero-hop mode). */
  fetchImpl?: (request: ReplayRequest) => Promise<ReplayResponse>;
};

/**
 * The `host` object handed to plugin code.
 *
 * Inside a plugin method every network method is *blocking*: it returns recorded data or an inert
 * placeholder (see `replay.ts`). Outside one it returns the placeholder, because a synchronous HTTP
 * call cannot exist in a browser; extension code uses the `*Async` variants instead.
 */
export class PluginHost {
  /** §6.4 `host.apiLevel`. */
  readonly apiLevel = HOST_API_LEVEL;
  /** §6.4 `host.language`. */
  readonly language: string | null;

  private readonly extraHeaders: Record<string, string>;
  private readonly sourceId: string;
  private readonly engine: ReplayEngine;
  private readonly transport: TransportFn;
  private readonly cache = new Map<string, { value: unknown; expiresAt: number }>();
  private readonly maxCacheEntries = 512;

  /** Non-null while a plugin method is executing under replay. */
  private serving:
    | ((request: ReplayRequest) => { kind: "response"; response: ReplayResponse } | { kind: "pending" })
    | null = null;

  constructor(options: PluginHostOptions = {}) {
    this.extraHeaders = { ...(options.extraHeaders ?? {}) };
    this.sourceId = options.sourceId ?? "source";
    this.language = options.language ?? null;
    this.transport = options.fetchImpl ?? defaultTransport;
    this.engine = new ReplayEngine(this.transport, {
      maxRequests: options.maxRequests ?? DEFAULT_MAX_REQUESTS,
      maxPasses: options.maxPasses ?? DEFAULT_MAX_PASSES,
      deadlineMs: options.callTimeoutMs ?? DEFAULT_CALL_TIMEOUT_MS,
      concurrency: DEFAULT_CONCURRENCY,
    });
  }

  // -----------------------------------------------------------------------------------------------
  // §6.1 Network
  // -----------------------------------------------------------------------------------------------

  /**
   * §6.1 `host.fetch(spec)` — GET/HEAD with retries; never throws on HTTP status.
   *
   * Also accepts the `fetch(url, options)` form.
   */
  fetch(spec: FetchSpec | string, options?: Omit<FetchSpec, "url">): HostFetchResult {
    const normalized: FetchSpec = typeof spec === "string" ? { url: spec, ...options } : spec;
    const method = resolveMethod(normalized.method);
    const response = this.replay({
      method,
      url: normalized.url,
      headers: this.mergeHeaders(normalized.headers),
    });
    if (response === null) return INERT_FETCH_RESULT;
    if (response.error) throw new Error(`host.fetch failed: ${response.error}`);
    return new HostFetchResult(
      response.status,
      response.headers,
      new TextEncoder().encode(method === "HEAD" ? "" : response.body),
    );
  }

  /** §6.1 `host.fetchText(spec)` — GET, throws on non-2xx. */
  fetchText(spec: FetchSpec): string {
    const result = this.fetch({ ...spec, method: "GET" });
    if (result.code === 0) return "";
    if (result.code < 200 || result.code >= 300) {
      // The replay engine has already retried transient statuses; surfacing the code here is the
      // plugin-visible outcome, matching the native host after its retries are exhausted.
      throw new Error(`HTTP ${result.code} for ${spec.url}`);
    }
    return result.text();
  }

  /** §6.1 `host.fetchJson(spec)` — GET + JSON parse. */
  fetchJson(spec: FetchSpec): unknown {
    const text = this.fetchText(spec);
    if (text === "") return null;
    return parseJsonOrThrow(text, spec.url);
  }

  /** §6.1 `host.fetchAll(specs, concurrency)` — every request is queued within a single pass. */
  fetchAll(specs: FetchSpec[], concurrency = 6): HostFetchResult[] {
    // The engine's batch resolver applies `concurrency` when it drains the pass's pending requests.
    void concurrency;
    return specs.map((spec) => this.fetch(spec));
  }

  /** §6.1 `host.isAccessible(url)` — HEAD reachability probe. */
  isAccessible(url: string): boolean {
    if (!url) return false;
    const response = this.replay({ method: "HEAD", url, headers: this.mergeHeaders() });
    if (response === null) return INERT_ACCESSIBLE;
    return response.status >= 200 && response.status < 300;
  }

  /** Async `host.fetch` for extension/UI code (plugin methods must use the sync form). */
  async fetchAsync(spec: FetchSpec | string, options?: Omit<FetchSpec, "url">): Promise<HostFetchResult> {
    const normalized: FetchSpec = typeof spec === "string" ? { url: spec, ...options } : spec;
    const method = resolveMethod(normalized.method);
    const response = await this.transport({
      method,
      url: normalized.url,
      headers: this.mergeHeaders(normalized.headers),
    });
    if (response.error) throw new Error(`host.fetch failed: ${response.error}`);
    return new HostFetchResult(
      response.status,
      response.headers,
      new TextEncoder().encode(method === "HEAD" ? "" : response.body),
    );
  }

  /** Async `host.fetchText` for extension/UI code. */
  async fetchTextAsync(spec: FetchSpec): Promise<string> {
    const result = await this.fetchAsync({ ...spec, method: "GET" });
    if (result.code < 200 || result.code >= 300) {
      throw new Error(`HTTP ${result.code} for ${spec.url}`);
    }
    return result.text();
  }

  /** Async `host.fetchJson` for extension/UI code. */
  async fetchJsonAsync(spec: FetchSpec): Promise<unknown> {
    return parseJsonOrThrow(await this.fetchTextAsync(spec), spec.url);
  }

  /** Async `host.isAccessible` for extension/UI code; never throws. */
  async isAccessibleAsync(url: string): Promise<boolean> {
    if (!url) return false;
    try {
      const result = await this.fetchAsync({ url, method: "HEAD" });
      return result.code >= 200 && result.code < 300;
    } catch {
      return false;
    }
  }

  // -----------------------------------------------------------------------------------------------
  // §6.2 HTML parsing — purely local, so genuinely synchronous
  // -----------------------------------------------------------------------------------------------

  /** §6.2 `host.parseHtml(htmlText, baseUrl?)`. */
  parseHtml(htmlText: string, baseUrl?: string | null): HostHtmlDoc {
    return new HostHtmlDoc(parseHtmlDocument(htmlText, baseUrl), baseUrl);
  }

  // -----------------------------------------------------------------------------------------------
  // §6.3 Smart extraction
  // -----------------------------------------------------------------------------------------------

  /** §6.3 `host.extractImages(htmlOrText, baseUrl?)` — port of `HostApiV1Impl.extractImages`. */
  extractImages(htmlOrText: string, baseUrl?: string | null): string[] {
    const base = baseUrl ?? "";
    const candidates = parseAllImageCandidateLists(htmlOrText, base);
    for (const candidate of candidates) {
      if (candidate.length > 0) return candidate;
    }
    return parseImagesFromDom(htmlOrText, base);
  }

  /** §6.3 `host.parsePageInfo(docOrHtml)` — port of `HostApiV1Impl.parsePageInfo`. */
  parsePageInfo(docOrHtml: HostHtmlDoc | string): PageInfo {
    const doc = typeof docOrHtml === "string" ? this.parseHtml(docOrHtml) : docOrHtml;
    const root = doc.document;

    let current = 1;
    const currentEl = root.querySelector(
      "strong.pg_current, .pagination .active, .page-item.active, .current-page",
    );
    if (currentEl) {
      const parsed = toPositiveInt(currentEl.textContent ?? "");
      if (parsed !== null) current = parsed;
    }

    let last = current;
    const pageFromHref = (href: string | null): number | null => {
      if (!href) return null;
      const match = /(?:[?&]|&amp;)page=(\d+)/.exec(href);
      return match ? Number.parseInt(match[1], 10) : null;
    };

    const endEl = root.querySelector("a.pg_end, a.page-end, a[title*='끝']");
    if (endEl) {
      const fromEnd = pageFromHref(endEl.getAttribute("href"));
      if (fromEnd !== null && fromEnd > last) last = fromEnd;
    }

    for (const el of Array.from(root.querySelectorAll("a.pg_page, .pagination a, .page-link"))) {
      const candidate = pageFromHref(el.getAttribute("href")) ?? toPositiveInt(el.textContent ?? "");
      if (candidate !== null && candidate > last) last = candidate;
    }

    return { currentPage: current, lastPage: last };
  }

  // -----------------------------------------------------------------------------------------------
  // §6.4 Text, parsing & utilities
  // -----------------------------------------------------------------------------------------------

  absUrl(url: string, baseUrl?: string | null): string {
    return resolveAbsUrl(url, baseUrl);
  }

  unescape(text: string | null | undefined): string {
    if (!text) return "";
    // Markup passes through untouched (Jsoup unescapes entities, it does not strip tags), so only
    // text without '<' is routed through the parser's full HTML5 entity table.
    if (!text.includes("<")) {
      const el = parseHtmlDocument("<html><body></body></html>").createElement("div");
      el.innerHTML = text;
      const decoded = el.textContent;
      if (typeof decoded === "string") return decoded;
    }
    return decodeEntities(text);
  }

  match(text: string | null | undefined, regex: string, groupIndex = 1): string | null {
    if (text == null) return null;
    try {
      return new RegExp(regex).exec(text)?.[groupIndex] ?? null;
    } catch {
      return null;
    }
  }

  digits(text: string | null | undefined): string {
    return (text ?? "").replace(/\D/g, "");
  }

  slug(text: string | null | undefined): string {
    return (text ?? "")
      .trim()
      .toLowerCase()
      .replace(/\s+/g, "_")
      .replace(/^_+|_+$/g, "");
  }

  json(text: string): unknown {
    return parseJsonOrThrow(text, "host.json");
  }

  utf8(bytes: Uint8Array): string {
    return new TextDecoder("utf-8").decode(bytes);
  }

  jsonFromBody(bytes: Uint8Array): unknown {
    return this.json(this.utf8(bytes));
  }

  /** §6.4 `host.evalJs` — evaluates site JS in an isolated scope. */
  evalJs(code: string, exportName = "galleryinfo"): unknown {
    return evaluateInSandbox(code, exportName);
  }

  evalSiteJs(kind: "galleryinfo" | "gg", text: string): unknown {
    return this.evalJs(text, kind);
  }

  log(level: string, message: string): void {
    const normalized = level.toUpperCase();
    const method = normalized === "ERROR" ? "error" : normalized === "WARN" ? "warn" : "log";
    console[method](`[js:${this.sourceId}] ${level}: ${message}`);
  }

  cacheGet(key: string): unknown {
    const entry = this.cache.get(key);
    if (!entry) return INERT_CACHE_MISS;
    if (Date.now() >= entry.expiresAt) {
      this.cache.delete(key);
      return INERT_CACHE_MISS;
    }
    return entry.value;
  }

  cachePut(key: string, value: unknown, ttlMs = 600_000): void {
    if (this.cache.size >= this.maxCacheEntries) {
      const oldest = this.cache.keys().next();
      if (!oldest.done) this.cache.delete(oldest.value);
    }
    this.cache.set(key, { value, expiresAt: Date.now() + ttlMs });
  }

  // -----------------------------------------------------------------------------------------------
  // plugin method execution
  // -----------------------------------------------------------------------------------------------

  /**
   * Runs one plugin method under replay.
   *
   * `host` network methods called from the plugin resolve from the recording, or record the request
   * and return a placeholder; `runMethod` keeps re-running `method` until nothing new is needed.
   */
  async runMethod<T>(key: string, method: () => T): Promise<T> {
    return this.engine.run(key, (serve) => {
      this.serving = serve;
      try {
        return method();
      } finally {
        this.serving = null;
      }
    });
  }

  /** Forgets recorded host traffic (source reloaded, credentials changed). */
  invalidateReplayCache(): void {
    this.engine.invalidate();
  }

  /**
   * Serves `request` from the current replay pass.
   *
   * Returns `null` outside a plugin method; callers then hand back an inert placeholder, so plugin
   * code never observes a promise — exactly like a blocking host with no data available.
   */
  private replay(request: ReplayRequest): ReplayResponse | null {
    const serve = this.serving;
    if (!serve) return null;
    const outcome = serve(request);
    return outcome.kind === "response" ? outcome.response : null;
  }

  /** Default headers + per-request headers; later entries win, as in `RequestPolicy`. */
  private mergeHeaders(headers?: Record<string, string>): Record<string, string> {
    return { ...this.extraHeaders, ...(headers ?? {}) };
  }
}

// -------------------------------------------------------------------------------------------------
// transport
// -------------------------------------------------------------------------------------------------

/**
 * Default transport: one HTTP request through the background worker, with the native host's backoff
 * for transient statuses.
 *
 * Transport failures are returned rather than thrown so the replay engine can decide whether the
 * request deserves another attempt.
 */
async function defaultTransport(request: ReplayRequest): Promise<ReplayResponse> {
  for (let attempt = 0; ; attempt += 1) {
    const response = await backgroundFetch({
      url: request.url,
      method: request.method,
      headers: request.headers,
      body: request.body,
    });
    const result: ReplayResponse = {
      ok: response.ok,
      status: response.status,
      headers: response.headers,
      body: response.body,
      error: response.error,
    };
    if (response.error) return result;
    if (!RETRYABLE_STATUS.has(response.status) || attempt >= BACKOFF_MS.length) return result;
    await sleep(BACKOFF_MS[attempt]);
  }
}

// -------------------------------------------------------------------------------------------------
// module helpers
// -------------------------------------------------------------------------------------------------

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function parseJsonOrThrow(text: string, origin: string): unknown {
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`Invalid JSON from ${origin}: ${describeError(error)}`);
  }
}

/** Replaces named and numeric character references; unknown entities are left verbatim. */
export function decodeEntities(text: string): string {
  return text.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);/g, (match, body: string) => {
    if (body.startsWith("#")) {
      const isHex = body[1] === "x" || body[1] === "X";
      const codePoint = Number.parseInt(isHex ? body.slice(2) : body.slice(1), isHex ? 16 : 10);
      if (!Number.isFinite(codePoint) || codePoint <= 0 || codePoint > 0x10ffff) return match;
      try {
        return String.fromCodePoint(codePoint);
      } catch {
        return match;
      }
    }
    return NAMED_ENTITIES[body.toLowerCase()] ?? match;
  });
}

function toPositiveInt(text: string): number | null {
  const digitsOnly = text.replace(/\D/g, "");
  if (!digitsOnly) return null;
  const parsed = Number.parseInt(digitsOnly, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function isImageUrl(url: string): boolean {
  const lower = url.toLowerCase();
  return IMAGE_EXTENSIONS.some((ext) => lower.includes(ext));
}

function isIgnoredImage(url: string): boolean {
  const lower = url.toLowerCase();
  return IGNORE_IMAGE_PATTERNS.some((pattern) => lower.includes(pattern));
}

function isComicImageUrl(url: string): boolean {
  return isImageUrl(url) && !isIgnoredImage(url);
}

function isValidContainerImageUrl(rawUrl: string): boolean {
  if (rawUrl.length < 3) return false;
  if (/^data:image\//i.test(rawUrl)) return true;
  const lower = rawUrl.toLowerCase();
  if (lower.startsWith("javascript:") || lower.startsWith("data:text/")) return false;
  return (
    lower.startsWith("http://") ||
    lower.startsWith("https://") ||
    lower.startsWith("//") ||
    lower.startsWith("/")
  );
}

function isValidArrayImageUrl(rawUrl: string): boolean {
  if (rawUrl.length < 3) return false;
  if (/\s/.test(rawUrl)) return false;
  const lower = rawUrl.toLowerCase();
  if (lower.startsWith("javascript:") || lower.startsWith("data:text/")) return false;
  if (isIgnoredImage(rawUrl)) return false;
  if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) return true;
  if (lower.startsWith("/") || lower.startsWith("./") || lower.startsWith("../")) return true;
  if (rawUrl.includes("/")) return true;
  return isImageUrl(lower);
}

function parseAllImageCandidateLists(html: string, baseUri: string): string[][] {
  const lists: string[][] = [];
  for (const match of html.matchAll(new RegExp(JS_IMG_ARRAY_RE))) {
    const urls: string[] = [];
    const seen = new Set<string>();
    for (const item of match[1].matchAll(new RegExp(JS_ARRAY_STRING_RE))) {
      const raw = item[1].trim().replace(/\\\//g, "/");
      if (!isValidArrayImageUrl(raw)) continue;
      const full = resolveAbsUrl(raw, baseUri);
      if (seen.has(full)) continue;
      seen.add(full);
      urls.push(full);
    }
    if (urls.length > 0) lists.push(urls);
  }
  return lists;
}

function parseImagesFromDom(html: string, baseUri: string): string[] {
  const doc = parseHtmlDocument(html);
  const containers = Array.from(doc.querySelectorAll(DOM_CONTAINER_SELECTOR));
  const useDocument = containers.length === 0;
  const scope: ParentNode = useDocument ? doc : containers[0];

  const urls: string[] = [];
  const seen = new Set<string>();
  const push = (raw: string): void => {
    const cleaned = raw.trim().replace(/\\\//g, "/");
    if (!cleaned) return;
    const accepted = useDocument
      ? isComicImageUrl(cleaned)
      : !isIgnoredImage(cleaned) && (isImageUrl(cleaned) || isValidContainerImageUrl(cleaned));
    if (!accepted) return;
    const full = resolveAbsUrl(cleaned, baseUri);
    if (seen.has(full)) return;
    seen.add(full);
    urls.push(full);
  };

  for (const img of Array.from(scope.querySelectorAll("img"))) {
    const raw =
      [img.getAttribute("data-src"), img.getAttribute("data-original"), img.getAttribute("data-lazy"), img.getAttribute("src")].find(
        (value) => value != null && value.trim() !== "",
      ) ?? "";
    push(raw);
  }

  for (const el of Array.from(scope.querySelectorAll("[style*='background']"))) {
    push(extractBackgroundUrl(el.getAttribute("style")) ?? "");
  }

  return urls;
}

/**
 * Evaluates site-provided JavaScript in an isolated scope.
 *
 * The native host runs this in a class-shuttered Rhino scope; in the browser `Function` is already
 * confined to the extension origin, but the globals the plugin spec forbids are still shadowed so a
 * hostile page script cannot reach extension APIs.
 */
function evaluateInSandbox(code: string, exportName: string): unknown {
  const forbidden = [
    "window",
    "document",
    "fetch",
    "XMLHttpRequest",
    "localStorage",
    "sessionStorage",
    "chrome",
    "browser",
    "globalThis",
    "self",
    "top",
    "parent",
    "importScripts",
  ];
  const sandbox = new Function(
    ...forbidden,
    `"use strict";
     ${code}
     return typeof ${exportName} !== "undefined" ? ${exportName} : undefined;`,
  );
  return sandbox(...forbidden.map(() => undefined));
}

// -------------------------------------------------------------------------------------------------
// source wrapper
// -------------------------------------------------------------------------------------------------

/** Raw `source` object shape a plugin defines (`var source = { ... }`). */
export type RawSource = {
  id?: unknown;
  displayName?: unknown;
  origin?: unknown;
  apiLevel?: unknown;
  catalogs?: unknown;
  searchPlaceholder?: unknown;
  userAgent?: unknown;
  referer?: unknown;
  extraHeaders?: unknown;
  episodePageSize?: unknown;
  notificationMode?: unknown;
  progressDisplay?: unknown;
  defaultLanguage?: unknown;
  emptyListingOk?: unknown;
  emptyEpisodesOk?: unknown;
  loadListing?: unknown;
  loadEpisodes?: unknown;
  resolveImages?: unknown;
  search?: unknown;
  suggest?: unknown;
  coverUrl?: unknown;
  resolveParent?: unknown;
  ownsHost?: unknown;
  imageFallbacks?: unknown;
  imageReferer?: unknown;
  useProxy?: unknown;
  supportsChapterNotifications?: unknown;
  notificationCandidates?: unknown;
  applyConfig?: unknown;
};

const NOTIFICATION_MODES = new Set<NotificationMode>(["NONE", "LATEST_INTERSECTION", "PER_FAVORITE"]);
const PROGRESS_DISPLAYS = new Set<ProgressDisplay>(["LAST_READ_ORDER", "READ_COUNT"]);

/**
 * A loaded plugin: normalised metadata plus the entry points the extension calls.
 *
 * Every method is async because of replay, even though the plugin sees a synchronous `host`.
 */
export class LoadedSource {
  readonly meta: SourceMeta;
  readonly host: PluginHost;

  private readonly raw: RawSource;
  private readonly timeoutMs: number;

  constructor(raw: RawSource, host: PluginHost, timeoutMs = DEFAULT_CALL_TIMEOUT_MS) {
    this.raw = raw;
    this.host = host;
    this.timeoutMs = timeoutMs;
    this.meta = normaliseMeta(raw);
  }

  get id(): string {
    return this.meta.id;
  }

  get displayName(): string {
    return this.meta.displayName;
  }

  get catalogs(): SourceCatalog[] {
    return this.meta.catalogs;
  }

  // --- required lifecycle methods (§4) -----------------------------------------------------------

  /** §4.1 `loadListing(catalogId, page)`. */
  async loadListing(catalogId: string, page = 1): Promise<ListingResult> {
    const result = normaliseItemPage(
      await this.call("loadListing", [catalogId, page]),
      this.meta.id,
      "loadListing",
    );
    if (result.items.length === 0 && !this.meta.emptyListingOk) {
      throw new Error(`source ${this.meta.id}: loadListing returned no items for "${catalogId}"`);
    }
    return result;
  }

  /** §4.2 `loadEpisodes(item, page)`. */
  async loadEpisodes(item: ToonItem, page = 1): Promise<EpisodeResult> {
    const result = normaliseEpisodePage(await this.call("loadEpisodes", [item, page]), this.meta.id);
    if (result.items.length === 0 && !this.meta.emptyEpisodesOk) {
      throw new Error(
        `source ${this.meta.id}: loadEpisodes returned no episodes for "${item?.title ?? ""}"`,
      );
    }
    return result;
  }

  /** §4.3 `resolveImages(episode, item)` — throws when nothing resolves. */
  async resolveImages(episode: EpisodeItem, item: ToonItem): Promise<string[]> {
    const urls = normaliseStringList(
      await this.call("resolveImages", [episode, item]),
      this.meta.id,
      "resolveImages",
    );
    if (urls.length === 0) {
      throw new Error(`source ${this.meta.id}: no images resolved for "${episode?.title ?? ""}"`);
    }
    return urls;
  }

  // --- optional methods (§5) ---------------------------------------------------------------------

  async search(query: SearchQuery): Promise<ToonItem[]> {
    if (typeof this.raw.search !== "function") return [];
    return normaliseItemList(await this.call("search", [query]), this.meta.id, "search");
  }

  async suggest(query: SearchQuery): Promise<SearchSuggestion[]> {
    if (typeof this.raw.suggest !== "function") return [];
    const value = await this.call("suggest", [query]);
    if (!Array.isArray(value)) {
      throw new Error(`source ${this.meta.id}: suggest must return an array`);
    }
    return value.filter(
      (entry): entry is SearchSuggestion =>
        isRecord(entry) && typeof entry.tag === "string" && typeof entry.ns === "string",
    );
  }

  async coverUrl(toonId: string): Promise<string | null> {
    if (typeof this.raw.coverUrl !== "function") return null;
    const value = await this.call("coverUrl", [toonId]);
    return typeof value === "string" && value.trim() !== "" ? value : null;
  }

  async resolveParent(
    item: ToonItem,
    choice: { slug: string; displayName: string },
    entryEpisodeId?: string | null,
  ): Promise<ToonItem | null> {
    if (typeof this.raw.resolveParent !== "function") return null;
    const value = await this.call("resolveParent", [item, choice, entryEpisodeId ?? null]);
    return isRecord(value) && typeof value.id === "string" ? (value as ToonItem) : null;
  }

  /** §5.2 — `origin` and its subdomains are always owned; the plugin adds external CDNs. */
  async ownsHost(hostname: string): Promise<boolean> {
    const key = hostname.toLowerCase().trim().replace(/^\.+|\.+$/g, "");
    if (!key) return false;

    const origin = originHostname(this.meta.origin);
    if (origin) {
      const normalize = (value: string): string => value.replace(/^www\./, "");
      const normalizedKey = normalize(key);
      const normalizedOrigin = normalize(origin);
      if (normalizedKey === normalizedOrigin || normalizedKey.endsWith(`.${normalizedOrigin}`)) {
        return true;
      }
    }

    if (typeof this.raw.ownsHost !== "function") return false;
    return (await this.call("ownsHost", [key])) === true;
  }

  async imageFallbacks(url: string): Promise<string[]> {
    if (typeof this.raw.imageFallbacks !== "function") return [];
    return normaliseStringList(await this.call("imageFallbacks", [url]), this.meta.id, "imageFallbacks");
  }

  async imageReferer(url: string): Promise<string> {
    if (typeof this.raw.imageReferer === "function") {
      const value = await this.call("imageReferer", [url]);
      if (typeof value === "string" && value.trim() !== "") return value;
    }
    return this.meta.referer || this.meta.origin;
  }

  /** §5.2 — routes through the proxy unless the plugin opts out (default `true`). */
  async useProxy(url: string): Promise<boolean> {
    if (typeof this.raw.useProxy !== "function") return true;
    return (await this.call("useProxy", [url])) !== false;
  }

  async supportsChapterNotifications(item: ToonItem): Promise<boolean> {
    if (typeof this.raw.supportsChapterNotifications !== "function") return false;
    return (await this.call("supportsChapterNotifications", [item])) === true;
  }

  async notificationCandidates(favorites: ToonItem[]): Promise<ToonItem[]> {
    if (typeof this.raw.notificationCandidates !== "function") return favorites;
    return normaliseItemList(
      await this.call("notificationCandidates", [favorites]),
      this.meta.id,
      "notificationCandidates",
    );
  }

  async applyConfig(config: { language: string | null }): Promise<void> {
    if (typeof this.raw.applyConfig !== "function") return;
    await this.call("applyConfig", [config]);
  }

  // --- invocation --------------------------------------------------------------------------------

  /** Runs one plugin method under replay, with the §1 call budget. */
  private async call(name: string, args: unknown[]): Promise<unknown> {
    const fn = (this.raw as Record<string, unknown>)[name];
    if (typeof fn !== "function") {
      throw new Error(`source ${this.meta.id}: ${name} is not implemented`);
    }
    const key = `${this.meta.id}:${name}:${stableArgs(args)}`;
    return withTimeout(
      this.host.runMethod(key, () => (fn as (...a: unknown[]) => unknown).apply(this.raw, args)),
      this.timeoutMs,
      `source ${this.meta.id}: ${name} timed out after ${this.timeoutMs}ms`,
    );
  }
}

// -------------------------------------------------------------------------------------------------
// plugin loading
// -------------------------------------------------------------------------------------------------

/** The script must define a top-level `source` object (§1). */
export function extractSource(script: string, origin?: string): RawSource {
  // A throwaway host: `extractSource` only validates shape and never runs plugin methods.
  return compilePlugin(script, new PluginHost({ sourceId: "preview" }), origin);
}

/**
 * Compiles plugin code with `host` bound in its closure, mirroring the native engine where `host` is
 * a global inside the Rhino scope.
 *
 * Browser globals the spec forbids (§1, §8) are shadowed by parameters so plugin code sees
 * `undefined` for them — the browser analogue of `JsSandbox.stripLiveConnect`.
 */
function compilePlugin(script: string, host: PluginHost, origin?: string): RawSource {
  const forbidden = [
    "window",
    "document",
    "fetch",
    "XMLHttpRequest",
    "localStorage",
    "sessionStorage",
    "chrome",
    "browser",
    "importScripts",
  ];
  const factory = new Function(
    "host",
    ...forbidden,
    `${script}
     if (typeof source === "undefined" || source === null) {
       throw new Error("plugin did not define a top-level 'source' object");
     }
     return source;`,
  );
  const source = factory(host, ...forbidden.map(() => undefined)) as unknown;

  if (!isRecord(source)) throw new Error("plugin 'source' must be an object");
  if (typeof source.id !== "string" || source.id.trim() === "") {
    throw new Error(`plugin source.id is required${origin ? ` (${origin})` : ""}`);
  }
  if (typeof source.displayName !== "string" || source.displayName.trim() === "") {
    throw new Error(`plugin ${source.id}: source.displayName is required`);
  }
  for (const method of ["loadListing", "loadEpisodes", "resolveImages"] as const) {
    if (typeof source[method] !== "function") {
      throw new Error(`plugin ${source.id}: source.${method} must be a function`);
    }
  }
  return source as RawSource;
}

/**
 * Compiles plugin JavaScript and binds it to a {@link PluginHost}.
 *
 * The host's default headers come from the plugin's own metadata (`extraHeaders`, `referer`,
 * `userAgent`), matching how the native engine builds its `RequestPolicy`. Compiling twice is
 * deliberate: the policy must exist before the plugin's closures capture the host.
 */
export function loadSource(
  script: string,
  options: PluginHostOptions & { origin?: string } = {},
): LoadedSource {
  const probe = compilePlugin(
    script,
    new PluginHost({ sourceId: "preview", fetchImpl: options.fetchImpl }),
    options.origin,
  );
  const meta = normaliseMeta(probe);

  const extraHeaders: Record<string, string> = { ...meta.extraHeaders };
  if (meta.userAgent) extraHeaders["User-Agent"] = meta.userAgent;
  if (meta.referer) extraHeaders["Referer"] = meta.referer;

  const host = new PluginHost({
    ...options,
    sourceId: meta.id,
    extraHeaders: { ...extraHeaders, ...(options.extraHeaders ?? {}) },
  });
  const raw = compilePlugin(script, host, options.origin);

  return new LoadedSource(raw, host, options.callTimeoutMs ?? DEFAULT_CALL_TIMEOUT_MS);
}

/** Convenience factory mirroring the task's `createPluginHost()` name. */
export function createPluginHost(options: PluginHostOptions = {}): PluginHost {
  return new PluginHost(options);
}

// -------------------------------------------------------------------------------------------------
// normalisation
// -------------------------------------------------------------------------------------------------

function normaliseMeta(raw: RawSource): SourceMeta {
  const id = requireString(raw.id, "source.id");
  const displayName = requireString(raw.displayName, "source.displayName");
  const origin = typeof raw.origin === "string" ? raw.origin.trim() : "";

  const apiLevel = Number.isFinite(raw.apiLevel) ? Number(raw.apiLevel) : 1;
  if (apiLevel < 1 || apiLevel > HOST_API_LEVEL) {
    throw new Error(`plugin ${id}: apiLevel ${apiLevel} is not supported (host is ${HOST_API_LEVEL})`);
  }

  const catalogs = normaliseCatalogs(raw.catalogs);
  return {
    id,
    displayName,
    origin,
    apiLevel,
    catalogs: catalogs.length > 0 ? catalogs : [{ id: "LATEST", label: "최신", paginated: true }],
    searchPlaceholder: typeof raw.searchPlaceholder === "string" ? raw.searchPlaceholder : "제목 검색",
    userAgent: typeof raw.userAgent === "string" && raw.userAgent.trim() !== "" ? raw.userAgent : null,
    referer: typeof raw.referer === "string" && raw.referer.trim() !== "" ? raw.referer : origin,
    extraHeaders: isRecord(raw.extraHeaders)
      ? Object.fromEntries(
          Object.entries(raw.extraHeaders).filter(
            (entry): entry is [string, string] => typeof entry[1] === "string",
          ),
        )
      : {},
    episodePageSize: Number.isFinite(raw.episodePageSize) ? Math.max(1, Number(raw.episodePageSize)) : 100,
    notificationMode: NOTIFICATION_MODES.has(raw.notificationMode as NotificationMode)
      ? (raw.notificationMode as NotificationMode)
      : "NONE",
    progressDisplay: PROGRESS_DISPLAYS.has(raw.progressDisplay as ProgressDisplay)
      ? (raw.progressDisplay as ProgressDisplay)
      : "LAST_READ_ORDER",
    defaultLanguage:
      typeof raw.defaultLanguage === "string" && raw.defaultLanguage.trim() !== ""
        ? raw.defaultLanguage
        : null,
    emptyListingOk: raw.emptyListingOk === true,
    emptyEpisodesOk: raw.emptyEpisodesOk === true,
  };
}

function normaliseCatalogs(value: unknown): SourceCatalog[] {
  if (!Array.isArray(value)) return [];
  const out: SourceCatalog[] = [];
  for (const entry of value) {
    if (!isRecord(entry)) continue;
    const id = typeof entry.id === "string" ? entry.id.trim() : "";
    if (!id) continue;
    out.push({
      id,
      label: typeof entry.label === "string" && entry.label.trim() !== "" ? entry.label : id,
      paginated: entry.paginated !== false,
    });
  }
  return out;
}

function normaliseItemPage(result: unknown, sourceId: string, method: string): ListingResult {
  const items = Array.isArray(result) ? result : isRecord(result) ? extractItems(result) : null;
  if (!items) throw new Error(`source ${sourceId}: ${method} must return { items, pageInfo }`);
  return { items: normaliseItemList(items, sourceId, method), pageInfo: extractPageInfo(result) };
}

function normaliseEpisodePage(result: unknown, sourceId: string): EpisodeResult {
  const items = Array.isArray(result) ? result : isRecord(result) ? extractItems(result) : null;
  if (!items) throw new Error(`source ${sourceId}: loadEpisodes must return { items, pageInfo }`);
  const pageInfo = extractPageInfo(result);
  // A plugin that knows the real episode count is the cheapest possible source of a total, so every
  // spelling it may use is carried through (`reportedEpisodeTotal` reads them back). Dropping them
  // here was why a correct plugin answer never reached the read model.
  return {
    ...extractReportedTotals(result),
    ...extractReportedTotals(isRecord(result) ? result.pageInfo : undefined),
    items: normaliseEpisodeList(items, sourceId),
    pageInfo,
  };
}

function extractItems(result: Record<string, unknown>): unknown[] | null {
  return Array.isArray(result.items) ? result.items : null;
}

/** Total fields a plugin stated on `result` or on `result.pageInfo`, kept verbatim. */
function extractReportedTotals(source: unknown): ReportedTotalFields {
  const record = isRecord(source) ? source : {};
  const out: ReportedTotalFields = {};
  for (const field of REPORTED_TOTAL_FIELDS) {
    const value = record[field];
    if (typeof value === "number" && Number.isFinite(value)) {
      out[field] = value;
    } else if (typeof value === "string" && value.trim() !== "") {
      // Plugins occasionally state the count as a numeric string; the read side parses it.
      out[field] = value;
    }
  }
  return out;
}

/**
 * Accepts `pageInfo: { currentPage, lastPage }` as well as the flat `{ currentPage, lastPage }`,
 * and keeps the total fields a plugin attached to either container.
 */
function extractPageInfo(result: unknown): PageInfo {
  const container = isRecord(result) && isRecord(result.pageInfo) ? result.pageInfo : result;
  const record = isRecord(container) ? container : {};
  const currentPage = toPageNumber(record.currentPage, 1);
  const lastPage = Math.max(currentPage, toPageNumber(record.lastPage, currentPage));
  return { ...extractReportedTotals(record), currentPage, lastPage };
}

function toPageNumber(value: unknown, fallback: number): number {
  const parsed = typeof value === "number" ? value : Number.parseInt(String(value ?? ""), 10);
  return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : fallback;
}

function normaliseItemList(value: unknown, sourceId: string, method: string): ToonItem[] {
  if (!Array.isArray(value)) throw new Error(`source ${sourceId}: ${method} must return an array`);
  return value.map((entry, index) => {
    if (!isRecord(entry)) {
      throw new Error(`source ${sourceId}: ${method} item #${index} is not an object`);
    }
    const id = typeof entry.id === "string" ? entry.id.trim() : "";
    if (!id) throw new Error(`source ${sourceId}: ${method} item #${index} is missing "id"`);
    return {
      ...(entry as ToonItem),
      id,
      title: typeof entry.title === "string" && entry.title.trim() !== "" ? entry.title : id,
      thumbUrl: typeof entry.thumbUrl === "string" ? entry.thumbUrl : "",
      href: typeof entry.href === "string" ? entry.href : "",
    };
  });
}

function normaliseEpisodeList(value: unknown, sourceId: string): EpisodeItem[] {
  if (!Array.isArray(value)) throw new Error(`source ${sourceId}: loadEpisodes must return an array`);
  return value.map((entry, index) => {
    if (!isRecord(entry)) {
      throw new Error(`source ${sourceId}: loadEpisodes item #${index} is not an object`);
    }
    const wrId = typeof entry.wrId === "string" ? entry.wrId.trim() : "";
    if (!wrId) throw new Error(`source ${sourceId}: loadEpisodes item #${index} is missing "wrId"`);
    return {
      ...(entry as EpisodeItem),
      wrId,
      title: typeof entry.title === "string" && entry.title.trim() !== "" ? entry.title : wrId,
      href: typeof entry.href === "string" ? entry.href : "",
    };
  });
}

function normaliseStringList(value: unknown, sourceId: string, method: string): string[] {
  if (!Array.isArray(value)) throw new Error(`source ${sourceId}: ${method} must return an array`);
  const out: string[] = [];
  const seen = new Set<string>();
  for (const entry of value) {
    if (typeof entry !== "string") continue;
    const trimmed = entry.trim();
    if (!trimmed || seen.has(trimmed)) continue;
    seen.add(trimmed);
    out.push(trimmed);
  }
  return out;
}

// -------------------------------------------------------------------------------------------------
// utilities
// -------------------------------------------------------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`plugin ${field} is required`);
  }
  return value.trim();
}

function originHostname(origin: string): string | null {
  if (!origin) return null;
  try {
    return new URL(origin).hostname.toLowerCase();
  } catch {
    return null;
  }
}

/** Stable key for the replay cache; must not depend on object identity. */
function stableArgs(args: unknown[]): string {
  return args
    .map((arg) => {
      if (arg === undefined) return "undefined";
      try {
        return JSON.stringify(arg, Object.keys(arg as Record<string, unknown>).sort());
      } catch {
        return String(arg);
      }
    })
    .join("|");
}

async function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  if (!Number.isFinite(ms) || ms <= 0) return promise;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(message)), ms);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}
