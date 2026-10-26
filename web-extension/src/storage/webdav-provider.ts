/**
 * WebDAV library source for the extension.
 *
 * Requests go through the background service worker (`backgroundFetch`) because `PROPFIND` is not a
 * CORS-safelisted method and cross-origin WebDAV servers rarely answer preflights. The service
 * worker holds the host permission, so no CORS dance is required.
 *
 * Behaviour mirrors `core/src/main/kotlin/source/network/WebDavFileSystem.kt`:
 * - `PROPFIND` with `Depth: 1`, `207 Multi-Status` parsed as `D:multistatus`;
 * - the requested collection's self entry is filtered out of its own listing;
 * - directory-ness comes from the presence of a `D:collection` element;
 * - responses containing a `DOCTYPE` are rejected (XXE / entity-expansion defence);
 * - `Basic` credentials are retried once on `401` after a challenge.
 */

import { backgroundFetch, base64ToBytes } from "../background-fetch";
import { describeError } from "../messaging";
import { archiveExtension, sortPageNames } from "./zip-provider";

export type WebDavConfig = {
  /** Server root, e.g. `https://dav.example.com/comics`. A trailing slash is added automatically. */
  url: string;
  username?: string;
  password?: string;
  /** Extra headers merged into every request. */
  headers?: Record<string, string>;
  /** Per-request timeout; defaults to 30s. */
  timeoutMs?: number;
};

/** Kind of node found in a WebDAV listing. */
export type WebDavEntryKind = "directory" | "archive" | "image" | "other";

export type WebDavEntry = {
  /** Path relative to the configured root, no leading or trailing slash. */
  path: string;
  /** Decoded name, for display. */
  name: string;
  directory: boolean;
  kind: WebDavEntryKind;
  sizeBytes: number;
  lastModified: number;
  etag: string | null;
  contentType: string | null;
};

/** A directory of loose images, ordered and ready for a reader. */
export type WebDavImageDirectory = {
  path: string;
  pages: Array<{ path: string; name: string; url: string; sizeBytes: number; lastModified: number }>;
};

const PROPFIND_BODY = `<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:getetag/>
    <d:getcontenttype/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>`;

const DAV_NS = "DAV:";

export class WebDavProvider {
  private readonly root: string;
  private readonly username: string;
  private readonly password: string;
  private readonly extraHeaders: Record<string, string>;
  private readonly timeoutMs: number;
  /** Cached directory listings, keyed by absolute request path. */
  private readonly listingCache = new Map<string, WebDavEntry[]>();

  constructor(config: WebDavConfig) {
    const url = (config.url ?? "").trim();
    if (!/^https?:\/\//i.test(url)) {
      throw new Error("WebDAV URL must start with http:// or https://");
    }
    this.root = url.endsWith("/") ? url : `${url}/`;
    this.username = config.username ?? "";
    this.password = config.password ?? "";
    this.extraHeaders = { ...(config.headers ?? {}) };
    this.timeoutMs = config.timeoutMs ?? 30_000;
  }

  /** Server root including the trailing slash. */
  get serverUrl(): string {
    return this.root;
  }

  /**
   * Verifies the connection and credentials by listing the root collection.
   *
   * Throws with a human-readable message on failure (401/403/404/timeouts).
   */
  async test(): Promise<WebDavEntry[]> {
    return this.list("");
  }

  /** Lists one collection (`Depth: 1`), excluding the collection itself. */
  async list(path = "", options: { refresh?: boolean } = {}): Promise<WebDavEntry[]> {
    const normalized = normalizePath(path);
    const requestUrl = this.urlFor(normalized);
    if (!options.refresh) {
      const cached = this.listingCache.get(requestUrl);
      if (cached) return [...cached];
    }

    const response = await this.requestWithAuth(directoryUrl(requestUrl), "PROPFIND", {
      Depth: "1",
      "Content-Type": 'application/xml; charset="utf-8"',
    }, PROPFIND_BODY);

    if (response.status === 404) {
      throw new Error(`WebDAV path not found: ${normalized || "/"}`);
    }
    if (response.status === 401 || response.status === 403) {
      throw new Error(`WebDAV access denied (HTTP ${response.status}). Check username/password.`);
    }
    if (response.status !== 207 && response.status !== 200) {
      throw new Error(`WebDAV PROPFIND failed (HTTP ${response.status}) for ${normalized || "/"}`);
    }

    const entries = parseMultiStatus(response.body, {
      rootPath: pathnameOf(this.root),
      requestPath: pathnameOf(requestUrl),
    }).filter((entry) => entry.path !== normalized);
    this.listingCache.set(requestUrl, entries);
    return [...entries];
  }

  /** Lists only sub-directories (for a folder browser). */
  async listDirectories(path = ""): Promise<WebDavEntry[]> {
    return (await this.list(path)).filter((entry) => entry.directory);
  }

  /** Lists only comic archives (`.zip` / `.cbz`) in a collection. */
  async listArchives(path = ""): Promise<WebDavEntry[]> {
    return (await this.list(path)).filter((entry) => entry.kind === "archive");
  }

  /**
   * Lists loose images in a collection, in natural page order.
   *
   * This is the "image directory" source shape: a folder of `001.jpg … 042.jpg` behaves like a CBZ.
   */
  async listImages(path = ""): Promise<WebDavEntry[]> {
    const images = (await this.list(path)).filter((entry) => entry.kind === "image");
    const byName = new Map(images.map((entry) => [entry.name, entry]));
    return sortPageNames(images.map((entry) => entry.name))
      .map((name) => byName.get(name))
      .filter((entry): entry is WebDavEntry => entry !== undefined);
  }

  /**
   * Resolves a directory of images into directly loadable URLs.
   *
   * `withCredentials` (default `true`) asks the caller to fetch pages through
   * {@link WebDavProvider.fetchImage} so Basic auth survives; plain `url` values only work for
   * servers that allow anonymous access.
   */
  async openImageDirectory(path = ""): Promise<WebDavImageDirectory> {
    const images = await this.listImages(path);
    if (images.length === 0) {
      throw new Error(`No images found in WebDAV folder: ${normalizePath(path) || "/"}`);
    }
    const normalized = normalizePath(path);
    return {
      path: normalized,
      pages: images.map((entry) => ({
        path: entry.path,
        name: entry.name,
        url: this.publicUrl(entry.path),
        sizeBytes: entry.sizeBytes,
        lastModified: entry.lastModified,
      })),
    };
  }

  /** Absolute URL for a stored path (percent-encoded per segment). */
  publicUrl(path: string): string {
    return this.urlFor(normalizePath(path));
  }

  /** Downloads a file as bytes — feed the result to `ZipProvider.open` for `.cbz`. */
  async download(path: string): Promise<Uint8Array> {
    const url = this.urlFor(normalizePath(path));
    let response = await this.sendBytes(url, {});
    if (response.status === 401 && this.username) {
      response = await this.sendBytes(url, { Authorization: this.basicAuthHeader() });
    }
    if (response.status === 401 || response.status === 403) {
      throw new Error(`WebDAV access denied (HTTP ${response.status}) for ${path}`);
    }
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`WebDAV download failed (HTTP ${response.status}) for ${path}`);
    }
    return response.bytes;
  }

  /**
   * Downloads an image as a blob URL, for authenticated servers where `<img src>` cannot send Basic
   * credentials. Remember to revoke the returned URL with {@link WebDavProvider.releaseBlobUrl}.
   */
  async fetchImage(path: string): Promise<string> {
    const url = this.urlFor(normalizePath(path));
    let response = await this.sendBytes(url, {});
    if (response.status === 401 && this.username) {
      response = await this.sendBytes(url, { Authorization: this.basicAuthHeader() });
    }
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`WebDAV image download failed (HTTP ${response.status}) for ${path}`);
    }
    const contentType = headerValue(response.headers, "content-type") ?? "application/octet-stream";
    const blob = new Blob([toArrayBuffer(response.bytes)], { type: contentType });
    return URL.createObjectURL(blob);
  }

  releaseBlobUrl(url: string): void {
    if (url.startsWith("blob:")) URL.revokeObjectURL(url);
  }

  /** Drops cached listings (after a sync or an upload from another device). */
  invalidateCache(): void {
    this.listingCache.clear();
  }

  // -----------------------------------------------------------------------------------------------
  // internals
  // -----------------------------------------------------------------------------------------------

  private urlFor(path: string): string {
    if (path === "") return this.root;
    const encoded = path
      .split("/")
      .filter((segment) => segment !== "")
      .map((segment) => encodeURIComponent(segment).replace(/%40/g, "@"))
      .join("/");
    return `${this.root}${encoded}`;
  }

  /** Sends the request, retrying once with Basic auth if the server answers 401. */
  private async requestWithAuth(
    url: string,
    method: string,
    headers: Record<string, string>,
    body?: string,
  ): Promise<{ status: number; headers: Record<string, string>; body: string }> {
    const baseHeaders: Record<string, string> = { ...this.extraHeaders, ...headers };
    const first = await this.send(url, method, baseHeaders, body);
    if (first.status !== 401 || !this.username) return first;

    // Servers commonly send an unparseable WWW-Authenticate: always retry with Basic explicitly.
    const retry = await this.send(
      url,
      method,
      { ...baseHeaders, Authorization: this.basicAuthHeader() },
      body,
    );
    return retry;
  }

  /** Binary send path (`responseType: "base64"`), used for downloads and inline images. */
  private async sendBytes(
    url: string,
    headers: Record<string, string>,
  ): Promise<{ status: number; headers: Record<string, string>; bytes: Uint8Array }> {
    const merged = { ...this.extraHeaders, ...headers };
    const response = await backgroundFetch({
      url,
      method: "GET",
      headers: merged,
      timeoutMs: this.timeoutMs,
      responseType: "base64",
    });
    if (response.error) {
      throw new Error(`WebDAV request failed (GET ${url}): ${response.error}`);
    }
    return {
      status: response.status,
      headers: response.headers,
      bytes: base64ToBytes(response.bodyBase64 ?? ""),
    };
  }

  private basicAuthHeader(): string {
    const raw = `${this.username}:${this.password}`;
    const bytes = new TextEncoder().encode(raw);
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return `Basic ${btoa(binary)}`;
  }

  private async send(
    url: string,
    method: string,
    headers: Record<string, string>,
    body?: string,
  ): Promise<{ status: number; headers: Record<string, string>; body: string }> {
    const response = await backgroundFetch({
      url,
      method,
      headers,
      body,
      timeoutMs: this.timeoutMs,
    });
    if (response.error) {
      throw new Error(`WebDAV request failed (${method} ${url}): ${response.error}`);
    }
    return { status: response.status, headers: response.headers, body: response.body };
  }
}

// -------------------------------------------------------------------------------------------------
// path + XML helpers
// -------------------------------------------------------------------------------------------------

/** Normalises to `a/b/c` (no leading/trailing slash); the root is `""`. */
export function normalizePath(path: string | null | undefined): string {
  return (path ?? "")
    .replace(/\\/g, "/")
    .split("/")
    .filter((segment) => segment !== "" && segment !== ".")
    .join("/");
}

export function joinPath(parent: string, child: string): string {
  return [normalizePath(parent), normalizePath(child)].filter(Boolean).join("/");
}

export function classifyEntry(name: string, directory: boolean, contentType: string | null): WebDavEntryKind {
  if (directory) return "directory";
  if (archiveExtension(name) !== null) return "archive";
  const ext = name.slice(name.lastIndexOf(".") + 1).toLowerCase();
  if (["jpg", "jpeg", "png", "webp", "avif", "gif"].includes(ext)) return "image";
  if (contentType?.startsWith("image/")) return "image";
  return "other";
}

/**
 * Parses a `D:multistatus` document into entries.
 *
 * `rootPath` is the collection the client treats as `/` — entry paths are relative to it, matching
 * `NetworkNode.path` in the Kotlin client. `requestPath` identifies the collection that was asked
 * for, which WebDAV always echoes in its own listing and which must not appear as a child.
 *
 * Responses containing a `DOCTYPE` are rejected outright: the same XXE / entity-expansion defence as
 * `WebDavFileSystem.parseListing`.
 */
export function parseMultiStatus(
  xml: string,
  options: string | { rootPath?: string; requestPath?: string } = "",
): WebDavEntry[] {
  if (/<!DOCTYPE/i.test(xml)) {
    throw new Error("WebDAV response contains a DOCTYPE declaration; refusing to parse");
  }

  const doc = new DOMParser().parseFromString(xml, "application/xml");
  // Browsers report malformed XML as a `<parsererror>` element instead of throwing.
  const parseError = doc.getElementsByTagName("parsererror")[0];
  if (parseError) {
    throw new Error(`Invalid WebDAV XML: ${parseError.textContent?.trim() ?? "parse error"}`);
  }

  const responses = Array.from(doc.getElementsByTagNameNS(DAV_NS, "response"));
  // Fallback for servers that omit the namespace (broken but common).
  const nodes = responses.length > 0 ? responses : Array.from(doc.getElementsByTagName("response"));

  const rootPath = normalizePath(typeof options === "string" ? options : options.rootPath);
  const requestPath = normalizePath(typeof options === "string" ? "" : options.requestPath);

  const entries: WebDavEntry[] = [];
  for (const node of nodes) {
    const href = firstText(node, "href");
    if (!href) continue;

    const selfPath = normalizePath(hrefPath(safeDecode(href)));
    const directory = node.getElementsByTagNameNS(DAV_NS, "collection").length > 0 ||
      node.getElementsByTagName("collection").length > 0;

    // The requested collection appears in its own Depth: 1 listing; it is not a child.
    if (requestPath && selfPath === requestPath) continue;

    const name = lastSegment(selfPath);
    if (!name) continue;

    const relative = relativePath(selfPath, rootPath);
    if (relative === null) continue;

    const contentType = firstText(node, "getcontenttype");
    entries.push({
      path: relative,
      name,
      directory,
      kind: classifyEntry(name, directory, contentType),
      sizeBytes: Number.parseInt(firstText(node, "getcontentlength") ?? "", 10) || 0,
      lastModified: parseHttpDate(firstText(node, "getlastmodified")),
      etag: firstText(node, "getetag"),
      contentType,
    });
  }

  // Stable, predictable ordering for the folder browser.
  return entries.sort((a, b) => {
    if (a.directory !== b.directory) return a.directory ? -1 : 1;
    return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" });
  });
}

function firstText(node: Element, localName: string): string | null {
  const namespaced = node.getElementsByTagNameNS(DAV_NS, localName);
  const found = namespaced.length > 0 ? namespaced[0] : node.getElementsByTagName(localName)[0];
  const value = found?.textContent?.trim();
  return value ? value : null;
}

/**
 * Extracts the path of an href.
 *
 * The href arrives already percent-decoded, so it must be split by hand: `URL.pathname` would
 * re-encode it (decoded `Series A` -> `Series%20A`).
 */
function hrefPath(href: string): string {
  const withoutQuery = href.replace(/[?#].*$/, "");
  if (!/^[a-z][a-z0-9+.-]*:/i.test(withoutQuery)) return withoutQuery;
  try {
    // Absolute hrefs are the norm; keep the path but not the re-encoded form.
    return decodeURIComponent(new URL(withoutQuery).pathname);
  } catch {
    return withoutQuery;
  }
}

function lastSegment(path: string): string {
  const index = path.lastIndexOf("/");
  return index < 0 ? path : path.slice(index + 1);
}

/** Converts a server-absolute path into a path relative to the configured root. */
function relativePath(selfPath: string, rootPath: string): string | null {
  if (!rootPath) return selfPath || null;
  if (selfPath === rootPath) return null;
  if (selfPath.startsWith(`${rootPath}/`)) {
    const relative = selfPath.slice(rootPath.length + 1);
    return relative || null;
  }
  // Servers may return an absolute path whose prefix differs (reverse-proxy mount points, or hrefs
  // already relative to the request): fall back to the trailing segment rather than drop the row.
  return lastSegment(selfPath) || null;
}

/** Absolute URL path of a URL, without a trailing slash; `""` when unparseable. */
function pathnameOf(url: string): string {
  try {
    return new URL(url).pathname;
  } catch {
    return "";
  }
}

/** WebDAV collections are addressed with a trailing slash. */
function directoryUrl(url: string): string {
  return url.endsWith("/") ? url : `${url}/`;
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function parseHttpDate(value: string | null): number {
  if (!value) return 0;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/** Narrows `Uint8Array` to the plain `ArrayBuffer` view `Blob` accepts. */
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  return copy;
}

function headerValue(headers: Record<string, string>, name: string): string | null {
  const target = name.toLowerCase();
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === target) return value;
  }
  return null;
}

export { describeError };
