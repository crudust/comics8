/**
 * Comics8 Web Reader — background service worker (Manifest V3).
 *
 * The worker is deliberately thin: it owns the capabilities extension pages cannot have on their
 * own, and nothing else.
 *
 * 1. `FETCH_REQUEST` — performs cross-origin HTTP requests with the extension's host permissions
 *    (HTML, JSON, images, ZIP/CBZ bytes) and rewrites `Referer` / `User-Agent` for manga sites that
 *    block hotlinks. See `./fetch-handler.ts`.
 * 2. `SET_REFERER_RULE` — stores and installs the per-domain header spoofs through
 *    `chrome.declarativeNetRequest` dynamic rules. See `./referer-rules.ts`.
 * 3. `chrome.action.onClicked` — the toolbar icon opens the reader as a full tab (there is no popup
 *    and no side panel). See `./action-handler.ts`.
 *
 * Every handler is installed synchronously at the top level, which MV3 requires: an async import or
 * a lazily attached listener loses the first event that woke the worker.
 */

import { installActionHandler } from "./action-handler";
import { installFetchHandler } from "./fetch-handler";
import { installRefererRuleHandler } from "./referer-rules";

/*
 * Both handlers must be installed synchronously at the top level. An `await import()` or a listener
 * attached inside an async callback loses the message that woke the worker, and MV3 workers are
 * terminated aggressively between events.
 */
const disposeFetch = installFetchHandler();
const disposeRefererRules = installRefererRuleHandler();
const disposeAction = installActionHandler();

// Lets the worker tear its listeners down during development HMR without leaking duplicates.
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    disposeFetch();
    disposeRefererRules();
    disposeAction();
  });
}

// A failed promise anywhere in the worker must not look like a crash to the caller.
self.addEventListener("unhandledrejection", (event) => {
  console.error("[comics8] unhandled rejection in service worker", event.reason);
});
