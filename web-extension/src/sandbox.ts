/**
 * Sandbox script: runs inside `src/sandbox.html` with `unsafe-eval` allowed.
 *
 * It does not have access to `chrome.*` APIs, so all network requests are forwarded
 * to the parent window (`src/index.html`), which fetches them via the background service worker.
 *
 * Every value that leaves this window goes through {@link sanitizeCloneable}: plugin code can return
 * DOM nodes, functions or cyclic graphs, and `postMessage` would otherwise reject the whole message
 * with `#<Object> could not be cloned`.
 */

import { loadSource, type LoadedSource } from "./sources/plugin-host";
import { sanitizeCloneable } from "./sources/serialize";
import type { ReplayRequest, ReplayResponse } from "./sources/replay";

/** A hanging network call must not freeze the replay engine forever. */
const FETCH_TIMEOUT_MS = 35_000;

type PendingFetch = {
  resolve: (res: ReplayResponse) => void;
  reject: (err: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

const pendingFetches = new Map<string, PendingFetch>();
let fetchCounter = 0;

function sendFetchToParent(request: ReplayRequest): Promise<ReplayResponse> {
  fetchCounter += 1;
  const fetchId = `fetch_${Date.now()}_${fetchCounter}`;

  return new Promise<ReplayResponse>((resolve, reject) => {
    // 35s timeout guard so hanging network calls don't freeze the replay engine indefinitely
    const timer = setTimeout(() => {
      pendingFetches.delete(fetchId);
      reject(new Error(`Sandbox fetch timed out for ${request.url}`));
    }, FETCH_TIMEOUT_MS);

    pendingFetches.set(fetchId, { resolve, reject, timer });
    window.parent.postMessage(
      sanitizeCloneable({
        action: "SANDBOX_FETCH_REQUEST",
        fetchId,
        request,
      }),
      "*",
    );
  });
}

/**
 * `postMessage` to the parent with a sanitised payload.
 *
 * Returns `false` when the message could not be delivered (detached window, or a value the browser
 * still refuses to clone); callers never rely on delivery for correctness.
 */
function postToParent(payload: Record<string, unknown>): boolean {
  const target = window.parent;
  if (!target) return false;
  try {
    target.postMessage(sanitizeCloneable(payload), "*");
    return true;
  } catch {
    return false;
  }
}

/** Sends a reply to the window that asked, falling back to the parent when `event.source` is gone. */
function reply(event: MessageEvent, payload: Record<string, unknown>): void {
  const message = sanitizeCloneable<Record<string, unknown>>(payload);
  const target = event.source as Window | null | undefined;
  if (target) {
    try {
      target.postMessage(message, { targetOrigin: "*" });
      return;
    } catch {
      // Fall through to the parent: a detached/mismatched source must not swallow the reply.
    }
  }
  try {
    window.parent?.postMessage(message, "*");
  } catch {
    // Nothing left to deliver to; the parent's own timeout will surface the failure.
  }
}

// Cached loaded sources inside sandbox: script -> LoadedSource
const loadedSourcesCache = new Map<string, LoadedSource>();

function getOrLoadSource(script: string): LoadedSource {
  const cached = loadedSourcesCache.get(script);
  if (cached) return cached;

  const loaded = loadSource(script, {
    fetchImpl: sendFetchToParent,
  });
  loadedSourcesCache.set(script, loaded);
  return loaded;
}

window.addEventListener("message", async (event) => {
  const data = event.data;
  if (!data || typeof data !== "object") return;

  // Handle incoming fetch results from parent
  if (data.action === "SANDBOX_FETCH_RESPONSE") {
    const pending = pendingFetches.get(data.fetchId);
    if (pending) {
      clearTimeout(pending.timer);
      pendingFetches.delete(data.fetchId);
      if (data.error) {
        pending.reject(new Error(data.error));
      } else {
        pending.resolve(data.response);
      }
    }
    return;
  }

  // Handle Ping
  if (data.action === "SANDBOX_PING") {
    reply(event, { id: data.id, action: "SANDBOX_PONG" });
    return;
  }

  // Handle GET_META
  if (data.action === "GET_META") {
    try {
      const source = getOrLoadSource(data.script);
      reply(event, {
        id: data.id,
        action: "GET_META_RESULT",
        ok: true,
        meta: source.meta,
        catalogs: source.catalogs,
      });
    } catch (err) {
      reply(event, {
        id: data.id,
        action: "GET_META_RESULT",
        ok: false,
        error: err instanceof Error ? err.message : String(err),
      });
    }
    return;
  }

  // Handle CALL_METHOD
  if (data.action === "CALL_METHOD") {
    try {
      const source = getOrLoadSource(data.script);
      const method = data.method as "loadListing" | "loadEpisodes" | "resolveImages" | "search";
      const args = Array.isArray(data.args) ? data.args : [];

      let result: unknown;
      if (method === "loadListing") {
        result = await source.loadListing(args[0], args[1]);
      } else if (method === "loadEpisodes") {
        result = await source.loadEpisodes(args[0], args[1]);
      } else if (method === "resolveImages") {
        result = await source.resolveImages(args[0], args[1]);
      } else if (method === "search") {
        result = await source.search(args[0]);
      } else {
        throw new Error(`Unsupported method: ${method}`);
      }

      reply(event, {
        id: data.id,
        action: "CALL_METHOD_RESULT",
        ok: true,
        result,
      });
    } catch (err) {
      reply(event, {
        id: data.id,
        action: "CALL_METHOD_RESULT",
        ok: false,
        error: err instanceof Error ? err.message : String(err),
      });
    }
  }
});

// Notify parent that sandbox is mounted
postToParent({ action: "SANDBOX_READY" });
