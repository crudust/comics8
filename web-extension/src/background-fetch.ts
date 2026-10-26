/**
 * Bridge from the DOM-free extension contexts to {@link ./messaging}, plus the handler entry point
 * the background service worker uses for `FETCH_REQUEST`.
 *
 * The request itself is executed in `./background/fetch-handler.ts`; this module only exposes the
 * page-side client wrapper and the matching `chrome.runtime.onMessage` installer. Which URLs may be
 * fetched is decided by `host_permissions` in the manifest, not here.
 */

import { installFetchHandler } from "./background/fetch-handler";
import {
  FETCH_REQUEST,
  nextRequestId,
  sendToBackground,
  type BackgroundRequest,
  type BackgroundResponse,
} from "./messaging";

export type FetchInit = {
  url: string;
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  timeoutMs?: number;
  redirect?: RequestRedirect;
  /** `"base64"` keeps binary responses intact (ZIP/CBZ, images). */
  responseType?: "text" | "base64";
};

/**
 * Performs an HTTP request through the background worker and returns the full response envelope
 * (including non-2xx statuses). Throws only on transport failure.
 */
export async function backgroundFetch(init: FetchInit): Promise<BackgroundResponse> {
  const request: BackgroundRequest = {
    action: FETCH_REQUEST,
    requestId: nextRequestId(),
    url: init.url,
    method: (init.method ?? "GET").toUpperCase(),
    headers: init.headers ?? {},
    body: init.body,
    timeoutMs: init.timeoutMs,
    redirect: init.redirect,
    responseType: init.responseType ?? "text",
  };
  return sendToBackground(request);
}

/** Convenience wrapper that decodes a `responseType: "base64"` body. */
export async function backgroundFetchBytes(init: FetchInit): Promise<{
  status: number;
  ok: boolean;
  headers: Record<string, string>;
  bytes: Uint8Array;
}> {
  const response = await backgroundFetch({ ...init, responseType: "base64" });
  if (response.error) {
    throw new Error(`Comics8: background fetch failed (${init.url}): ${response.error}`);
  }
  return {
    status: response.status,
    ok: response.ok,
    headers: response.headers,
    bytes: base64ToBytes(response.bodyBase64 ?? ""),
  };
}

export function base64ToBytes(base64: string): Uint8Array {
  if (!base64) return new Uint8Array(0);
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function bytesToBase64(bytes: Uint8Array): string {
  if (bytes.length === 0) return "";
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

/**
 * Installs the `FETCH_REQUEST` handler on the service worker's `chrome.runtime.onMessage`.
 *
 * Delegates to {@link installFetchHandler}: there must be exactly one `FETCH_REQUEST`
 * implementation, because `chrome.runtime.onMessage` returns the *first* listener's response and two
 * handlers would silently race. See `src/background/fetch-handler.ts` for the real implementation
 * (base64/data-URL bodies, header sanitising, `Referer`/`User-Agent` spoofing).
 *
 * Returns an unsubscribe function. Call once from the background worker's top level.
 */
export function installBackgroundFetchHandler(): () => void {
  return installFetchHandler();
}
