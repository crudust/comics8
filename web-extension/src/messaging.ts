/**
 * Cross-context message protocol between extension pages and the background service worker.
 *
 * Everything that touches the network goes through the service worker (`FETCH_REQUEST`) so that
 * host permissions — not CORS — decide what the extension may read. Source plugins, WebDAV and the
 * sync client all funnel through {@link sendToBackground}.
 */

export const FETCH_REQUEST = "FETCH_REQUEST" as const;
export const FETCH_RESPONSE = "FETCH_RESPONSE" as const;

export type FetchRequestMessage = {
  action: typeof FETCH_REQUEST;
  /** Unique id correlating a request with its {@link FetchResponseMessage}. */
  requestId: string;
  url: string;
  method: string;
  headers: Record<string, string>;
  /** Request body as UTF-8 text. */
  body?: string;
  /** Milliseconds before the background worker aborts the request. */
  timeoutMs?: number;
  /** `"follow" | "error" | "manual"`. */
  redirect?: RequestRedirect;
  /** `"text"` (default) round-trips as UTF-8; `"base64"` preserves binary bytes (ZIP, images). */
  responseType?: "text" | "base64";
};

export type FetchResponseMessage = {
  action: typeof FETCH_RESPONSE;
  requestId: string;
  ok: boolean;
  status: number;
  statusText: string;
  /** Final URL after redirects; equals the request URL on transport failure. */
  url: string;
  headers: Record<string, string>;
  /** UTF-8 body. Empty when `responseType` was `"base64"`. */
  body: string;
  /** Base64 body; only present when the request asked for `responseType: "base64"`. */
  bodyBase64?: string;
  /** Set when the request never produced an HTTP response (DNS, CORS, timeout, ...). */
  error?: string;
};

export type BackgroundRequest = FetchRequestMessage;
export type BackgroundResponse = FetchResponseMessage;

type SendMessageFn = (message: BackgroundRequest) => Promise<BackgroundResponse>;
type RuntimeApi = typeof chrome.runtime;

function runtimeApi(): RuntimeApi | undefined {
  return typeof chrome !== "undefined" && chrome.runtime ? chrome.runtime : undefined;
}

/**
 * Sends a request to the background service worker and returns its raw response.
 *
 * Rejects when no service worker is reachable, so callers never mistake a transport failure for an
 * empty HTTP body.
 */
export async function sendToBackground(message: BackgroundRequest): Promise<BackgroundResponse> {
  const runtime = runtimeApi();
  const send = runtime?.sendMessage as SendMessageFn | undefined;
  if (typeof send !== "function") {
    throw new Error(
      "Comics8: background service worker is unreachable (chrome.runtime.sendMessage is unavailable)",
    );
  }

  let response: BackgroundResponse | undefined;
  try {
    // Chrome types declare sendMessage as callback-based; MV3 also returns a promise.
    response = await (send as unknown as (msg: unknown) => Promise<unknown>).call(runtime, message) as
      | BackgroundResponse
      | undefined;
  } catch (error) {
    throw new Error(
      `Comics8: background request failed (${message.method} ${message.url}): ${describeError(error)}`,
    );
  }

  const lastError = runtimeApi()?.lastError;
  if (lastError?.message) {
    throw new Error(`Comics8: background request failed: ${lastError.message}`);
  }

  if (!response || response.action !== FETCH_RESPONSE || response.requestId !== message.requestId) {
    throw new Error("Comics8: background service worker returned a malformed response");
  }
  return response;
}

export function describeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

/** Monotonic request id; only needs to be unique per extension session. */
let requestSeq = 0;
export function nextRequestId(): string {
  requestSeq += 1;
  return `${Date.now().toString(36)}-${requestSeq.toString(36)}`;
}
