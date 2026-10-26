/**
 * Main-thread bridge for communicating with the plugin sandbox iframe.
 *
 * Runs inside the extension page (`src/index.html`). Manages the hidden `src/sandbox.html` iframe,
 * forwards network requests from the sandbox to the background service worker, and provides typed
 * async wrappers for compiling plugins and invoking their methods.
 */

import { backgroundFetch } from "../background-fetch";
import { sanitizeCloneable } from "./serialize";
import type { SourceMeta, SourceCatalog, ToonItem, EpisodeItem, ListingResult, EpisodeResult } from "./types";
import type { ReplayRequest, ReplayResponse } from "./replay";

/** Sandbox replies must arrive before the caller gives up; the sandbox itself budgets 35s. */
const SANDBOX_TIMEOUT_MS = 40_000;

let sandboxIframe: HTMLIFrameElement | null = null;
let sandboxReadyPromise: Promise<void> | null = null;
let reqCounter = 0;

const pendingRequests = new Map<
  string,
  {
    resolve: (res: any) => void;
    reject: (err: Error) => void;
  }
>();

/** `postMessage` to the sandbox with a clone-safe payload (see `./serialize`). */
function postToSandbox(target: Window, payload: Record<string, unknown>): void {
  target.postMessage(sanitizeCloneable(payload), "*");
}

export function isExtensionContext(): boolean {
  return typeof chrome !== "undefined" && typeof chrome.runtime?.getURL === "function";
}

export function ensureSandbox(): Promise<void> {
  if (!isExtensionContext()) {
    return Promise.resolve();
  }

  if (sandboxReadyPromise) return sandboxReadyPromise;

  sandboxReadyPromise = new Promise<void>((resolve) => {
    if (typeof document === "undefined") {
      resolve();
      return;
    }

    const iframe = document.createElement("iframe");
    iframe.src = chrome.runtime.getURL("src/sandbox.html");
    iframe.style.display = "none";
    iframe.setAttribute("sandbox", "allow-scripts");
    sandboxIframe = iframe;

    const onMessage = async (event: MessageEvent) => {
      const data = event.data;
      if (!data || typeof data !== "object") return;

      // Sandbox is ready
      if (data.action === "SANDBOX_READY") {
        resolve();
        return;
      }

      // Sandbox needs a background fetch
      if (data.action === "SANDBOX_FETCH_REQUEST") {
        const { fetchId, request } = data as { fetchId: string; request: ReplayRequest };
        try {
          const bgReply = await backgroundFetch({
            url: request.url,
            method: request.method,
            headers: request.headers,
            body: request.body,
            responseType: "text",
          });

          const response: ReplayResponse = {
            ok: bgReply.ok,
            status: bgReply.status,
            headers: bgReply.headers,
            body: typeof bgReply.body === "string" ? bgReply.body : "",
            error: bgReply.error,
          };

          iframe.contentWindow?.postMessage(
            sanitizeCloneable({ action: "SANDBOX_FETCH_RESPONSE", fetchId, response }),
            "*",
          );
        } catch (err) {
          iframe.contentWindow?.postMessage(
            sanitizeCloneable({
              action: "SANDBOX_FETCH_RESPONSE",
              fetchId,
              error: err instanceof Error ? err.message : String(err),
            }),
            "*",
          );
        }
        return;
      }

      // Method or Meta results
      if (data.id && pendingRequests.has(data.id)) {
        const pending = pendingRequests.get(data.id)!;
        pendingRequests.delete(data.id);
        if (data.ok) {
          pending.resolve(data);
        } else {
          pending.reject(new Error(data.error || "Sandbox call failed"));
        }
      }
    };

    window.addEventListener("message", onMessage);
    document.body.appendChild(iframe);
  });

  return sandboxReadyPromise;
}

export async function sandboxGetMeta(
  script: string,
): Promise<{ meta: SourceMeta; catalogs: SourceCatalog[] }> {
  await ensureSandbox();
  if (!sandboxIframe?.contentWindow) {
    throw new Error("Sandbox iframe is not available");
  }

  reqCounter += 1;
  const id = `meta_${Date.now()}_${reqCounter}`;

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingRequests.delete(id);
      reject(new Error("Timeout while inspecting plugin metadata in sandbox"));
    }, SANDBOX_TIMEOUT_MS);

    pendingRequests.set(id, {
      resolve: (data) => {
        clearTimeout(timer);
        resolve({ meta: data.meta, catalogs: data.catalogs });
      },
      reject: (err) => {
        clearTimeout(timer);
        reject(err);
      },
    });
    postToSandbox(sandboxIframe!.contentWindow!, { id, action: "GET_META", script });
  });
}

export async function sandboxCallMethod<T = unknown>(
  script: string,
  method: "loadListing" | "loadEpisodes" | "resolveImages" | "search",
  args: unknown[],
): Promise<T> {
  await ensureSandbox();
  if (!sandboxIframe?.contentWindow) {
    throw new Error("Sandbox iframe is not available");
  }

  reqCounter += 1;
  const id = `call_${Date.now()}_${reqCounter}`;

  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingRequests.delete(id);
      reject(new Error(`Timeout while invoking plugin method "${method}" in sandbox`));
    }, SANDBOX_TIMEOUT_MS);

    pendingRequests.set(id, {
      resolve: (data) => {
        clearTimeout(timer);
        resolve(data.result as T);
      },
      reject: (err) => {
        clearTimeout(timer);
        reject(err);
      },
    });
    postToSandbox(sandboxIframe!.contentWindow!, { id, action: "CALL_METHOD", script, method, args });
  });
}

export class SandboxLoadedSource {
  readonly id: string;
  readonly displayName: string;
  readonly meta: SourceMeta;
  readonly catalogs: SourceCatalog[];
  private readonly script: string;

  constructor(script: string, meta: SourceMeta, catalogs: SourceCatalog[]) {
    this.script = script;
    this.id = meta.id;
    this.displayName = meta.displayName;
    this.meta = meta;
    this.catalogs = catalogs;
  }

  loadListing(catalogId: string, page = 1): Promise<ListingResult> {
    return sandboxCallMethod<ListingResult>(this.script, "loadListing", [catalogId, page]);
  }

  loadEpisodes(item: ToonItem, page = 1): Promise<EpisodeResult> {
    return sandboxCallMethod<EpisodeResult>(this.script, "loadEpisodes", [item, page]);
  }

  resolveImages(episode: EpisodeItem, item: ToonItem): Promise<string[]> {
    return sandboxCallMethod<string[]>(this.script, "resolveImages", [episode, item]);
  }

  search(query: any, page = 1): Promise<ListingResult> {
    return sandboxCallMethod<ListingResult>(this.script, "search", [query, page]);
  }
}

