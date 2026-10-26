/**
 * `FETCH_REQUEST` — the extension's single network egress.
 *
 * Extension pages cannot bypass CORS, and `fetch()` silently drops forbidden request headers such as
 * `Referer` and `User-Agent`. Both problems are solved here, in the background service worker:
 *
 * - the request runs with the extension's `host_permissions` (`<all_urls>`), so cross-origin pages and
 *   hotlink-protected images are readable;
 * - `Referer` / `User-Agent` from the message are installed as `declarativeNetRequest` rules (see
 *   `referer-rules.ts`) *before* the request is issued, so the network stack sends them even though
 *   JavaScript may not set them.
 *
 * Two response shapes are accepted so existing callers keep working:
 *
 * - **envelope** (`requestId` present): `{ action: "FETCH_RESPONSE", requestId, ok, status, headers,
 *   body, bodyBase64? }` — what `sendToBackground()` in `messaging.ts` validates;
 * - **plain** (no `requestId`): the same fields plus `data`, `text`, `json` and `dataUrl`
 *   conveniences for one-shot callers and page contexts.
 *
 * The payload is never interpreted beyond the explicitly requested `responseType`, and binaries are
 * only decoded as base64/data URLs when asked for, so a 40 MB CBZ never round-trips through UTF-8.
 */

import { FETCH_REQUEST, FETCH_RESPONSE, describeError } from "../messaging";
import { ensureRefererRule } from "./referer-rules";

/** `text` (default), `json`, `base64`, or a ready-to-use `data-url`. */
export type FetchResponseType = "text" | "json" | "base64" | "data-url" | "dataUrl";

export type FetchRequest = {
  action: "FETCH_REQUEST";
  /** Present when the caller expects an envelope response; generated otherwise. */
  requestId?: string;
  url: string;
  method?: string;
  headers?: Record<string, string>;
  /** UTF-8 request body. */
  body?: string | null;
  timeoutMs?: number;
  redirect?: RequestRedirect;
  responseType?: FetchResponseType;
  /** Convenience overrides; equivalent to putting them in `headers`. */
  referer?: string | null;
  userAgent?: string | null;
  /** Refuse bodies larger than this (defaults to {@link DEFAULT_MAX_BYTES}). */
  maxBytes?: number;
};

export type FetchResult = {
  action: typeof FETCH_RESPONSE;
  requestId: string;
  ok: boolean;
  status: number;
  statusText: string;
  /** Final URL after redirects; the requested URL on transport failure. */
  url: string;
  headers: Record<string, string>;
  /** UTF-8 body. Empty for binary and data-URL responses. */
  body: string;
  /** Convenience alias of {@link body}. */
  text: string;
  /** Decoded JSON, only for `responseType: "json"` and valid JSON. */
  json?: unknown;
  /** Base64 payload, only for `"base64"` / `"data-url"` responses. */
  bodyBase64?: string;
  /** `data:<mime>;base64,…`, only for `"data-url"` responses. */
  dataUrl?: string;
  /** `data` mirrors `json` / `dataUrl` / `body`, whichever the caller asked for. */
  data: unknown;
  /** Set when no HTTP response was produced (bad URL, DNS, timeout, CORS, oversized body, …). */
  error?: string;
};

export const DEFAULT_TIMEOUT_MS = 30_000;
export const DEFAULT_MAX_BYTES = 64 * 1024 * 1024;

/**
 * Headers JavaScript must not set. They are either forbidden by `fetch()` (so they would be dropped
 * silently anyway) or would let a page smuggle request framing past the browser.
 */
const STRIPPED_REQUEST_HEADERS = new Set([
  "accept-charset",
  "accept-encoding",
  "access-control-request-headers",
  "access-control-request-method",
  "connection",
  "content-length",
  "cookie",
  "cookie2",
  "date",
  "dnt",
  "expect",
  "host",
  "keep-alive",
  "origin",
  "referer",
  "set-cookie",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "user-agent",
  "via",
]);

const ALLOWED_PROTOCOLS = new Set(["http:", "https:", "data:"]);

export type InstallOptions = {
  /** Overrides `fetch` (unit tests). Defaults to the global. */
  fetchImpl?: typeof fetch;
};

/**
 * Installs the `FETCH_REQUEST` listener on `chrome.runtime.onMessage`.
 *
 * Returns an unsubscribe function. Call once from the service worker's top level.
 */
export function installFetchHandler(options: InstallOptions = {}): () => void {
  const onMessage = chromeApi()?.runtime?.onMessage;

  const listener = (
    message: unknown,
    _sender: chrome.runtime.MessageSender,
    sendResponse: (response: FetchResult) => void,
  ): boolean => {
    if (!isFetchRequest(message)) return false;

    void performFetch(message, options.fetchImpl).then(sendResponse, (error: unknown) => {
      sendResponse(failure(message, describeError(error)));
    });
    // Keep the channel open for the async reply.
    return true;
  };

  const typed = listener as unknown as Parameters<NonNullable<typeof onMessage>["addListener"]>[0];
  onMessage?.addListener(typed);

  return () => {
    onMessage?.removeListener?.(typed);
  };
}

export function isFetchRequest(message: unknown): message is FetchRequest {
  return (
    isRecord(message) &&
    message.action === FETCH_REQUEST &&
    typeof message.url === "string" &&
    message.url.trim() !== ""
  );
}

/** Performs one request and always resolves with a result envelope (never rejects). */
export async function performFetch(
  request: FetchRequest,
  fetchImpl: typeof fetch = fetch,
): Promise<FetchResult> {
  const requestId = request.requestId ?? nextRequestId();

  let url: URL;
  try {
    url = new URL(request.url);
  } catch {
    return failure(request, `Invalid URL: ${request.url}`, requestId);
  }
  if (!ALLOWED_PROTOCOLS.has(url.protocol)) {
    return failure(request, `Unsupported URL scheme: ${url.protocol}`, requestId);
  }

  const method = (request.method ?? "GET").toUpperCase();
  // Read the spoofed headers from the raw map first: `sanitizeHeaders()` drops them because
  // `fetch()` may not send them, and the DNR rule below is what actually puts them on the wire.
  const rawHeaders = request.headers ?? {};
  const referer =
    normalizeHeaderValue(request.referer) ??
    normalizeHeaderValue(headerValue(rawHeaders, "referer"));
  const userAgent =
    normalizeHeaderValue(request.userAgent) ??
    normalizeHeaderValue(headerValue(rawHeaders, "user-agent"));
  const headers = sanitizeHeaders(rawHeaders);

  // Install the spoof before the request so the network stack applies it. A failure here must not
  // fail the fetch: most sites work fine without it, and the caller still gets the HTTP result.
  if (referer || userAgent) {
    try {
      await ensureRefererRule({ url: url.toString(), referer, userAgent });
    } catch {
      // Ignore: the request proceeds with the browser's default headers.
    }
  }

  const responseType: FetchResponseType = request.responseType ?? "text";
  const wantsBinary = responseType === "base64" || responseType === "data-url" || responseType === "dataUrl";

  const init: RequestInit = {
    method,
    headers,
    redirect: request.redirect ?? "follow",
    // Extension fetches are cross-site by definition; cookies would leak the user's sessions.
    credentials: "omit",
  };
  if (request.body != null && method !== "GET" && method !== "HEAD") {
    init.body = request.body;
  }

  const controller = new AbortController();
  const timeoutMs = clampTimeout(request.timeoutMs);
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  init.signal = controller.signal;

  try {
    const response = await fetchImpl(url.toString(), init);
    const responseHeaders: Record<string, string> = {};
    response.headers.forEach((value, key) => {
      responseHeaders[key] = value;
    });

    const finalUrl = response.url || url.toString();
    const base = {
      action: FETCH_RESPONSE,
      requestId,
      ok: response.ok,
      status: response.status,
      statusText: response.statusText,
      url: finalUrl,
      headers: responseHeaders,
      body: "",
      text: "",
      data: "",
    } satisfies FetchResult;

    if (method === "HEAD" || response.status === 204 || response.status === 205) {
      return { ...base, data: responseType === "json" ? null : "" };
    }

    const maxBytes = clampMaxBytes(request.maxBytes);
    const declared = Number.parseInt(responseHeaders["content-length"] ?? "", 10);
    if (Number.isFinite(declared) && declared > maxBytes) {
      return {
        ...base,
        ok: false,
        error: `Response is ${declared} bytes, above the ${maxBytes} byte limit`,
        data: null,
      };
    }

    if (wantsBinary) {
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (bytes.byteLength > maxBytes) {
        return {
          ...base,
          ok: false,
          error: `Response is ${bytes.byteLength} bytes, above the ${maxBytes} byte limit`,
          data: null,
        };
      }

      const encoded = bytesToBase64(bytes);
      const mimeType = (responseHeaders["content-type"] ?? "application/octet-stream")
        .split(";")[0]
        .trim() || "application/octet-stream";
      const dataUrl = `data:${mimeType};base64,${encoded}`;
      return {
        ...base,
        bodyBase64: encoded,
        dataUrl,
        data: responseType === "base64" ? encoded : dataUrl,
      };
    }

    const text = await response.text();
    if (responseType === "json") {
      const parsed = tryParseJson(text);
      return parsed.ok
        ? { ...base, body: text, text, json: parsed.value, data: parsed.value }
        : {
            ...base,
            body: text,
            text,
            data: text,
            error: `Invalid JSON: ${parsed.error}`,
          };
    }

    return { ...base, body: text, text, data: text };
  } catch (error) {
    return failure(request, describeError(error), requestId);
  } finally {
    clearTimeout(timer);
  }
}

// ------------------------------------------------------------------------------------------------
// helpers
// ------------------------------------------------------------------------------------------------

function failure(
  request: FetchRequest,
  error: string,
  requestId = request.requestId ?? nextRequestId(),
): FetchResult {
  return {
    action: FETCH_RESPONSE,
    requestId,
    ok: false,
    status: 0,
    statusText: "",
    url: request.url,
    headers: {},
    body: "",
    text: "",
    data: null,
    error,
  };
}

/** Drops forbidden/framing headers and normalises names; keeps the caller's last value. */
function sanitizeHeaders(headers: Record<string, string> | undefined): Record<string, string> {
  const result: Record<string, string> = {};
  if (!headers) return result;

  for (const [rawName, rawValue] of Object.entries(headers)) {
    const name = rawName.trim().toLowerCase();
    if (!name) continue;
    if (STRIPPED_REQUEST_HEADERS.has(name)) continue;
    if (name.startsWith("proxy-") || name.startsWith("sec-")) continue;
    if (typeof rawValue !== "string") continue;
    if (/[\r\n]/.test(rawValue)) continue;
    result[name] = rawValue;
  }
  return result;
}

function headerValue(headers: Record<string, string>, name: string): string | undefined {
  for (const [key, value] of Object.entries(headers)) {
    if (key.trim().toLowerCase() === name) return value;
  }
  return undefined;
}

function normalizeHeaderValue(value: string | null | undefined): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (!trimmed || /[\r\n]/.test(trimmed)) return undefined;
  return trimmed;
}

function clampTimeout(value: number | undefined): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    return DEFAULT_TIMEOUT_MS;
  }
  return Math.min(Math.max(Math.trunc(value), 1_000), 120_000);
}

function clampMaxBytes(value: number | undefined): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    return DEFAULT_MAX_BYTES;
  }
  return Math.min(Math.trunc(value), 256 * 1024 * 1024);
}

function tryParseJson(text: string): { ok: true; value: unknown } | { ok: false; error: string } {
  if (text.trim() === "") return { ok: false, error: "empty response body" };
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (error) {
    return { ok: false, error: describeError(error) };
  }
}

/** Chunked so a multi-megabyte body cannot blow the argument limit of `String.fromCharCode`. */
export function bytesToBase64(bytes: Uint8Array): string {
  if (bytes.length === 0) return "";
  const chunkSize = 0x8000;
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

let requestSeq = 0;
function nextRequestId(): string {
  requestSeq += 1;
  return `bg-${Date.now().toString(36)}-${requestSeq.toString(36)}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function chromeApi(): typeof chrome | undefined {
  return (globalThis as { chrome?: typeof chrome }).chrome;
}
