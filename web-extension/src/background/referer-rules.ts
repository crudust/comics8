/**
 * `SET_REFERER_RULE` / `GET_REFERER_RULES` — per-domain `Referer` / `User-Agent` spoofing.
 *
 * `fetch()` refuses to set forbidden request headers such as `Referer` and `User-Agent`, so image
 * hotlink protection cannot be bypassed from JavaScript. `declarativeNetRequest` is the only
 * supported way: a `modifyHeaders` rule rewrites those headers in the network stack, which applies to
 * the service worker's own `fetch()` calls and to `<img>` loads in extension pages.
 *
 * Ownership of the desired state lives here:
 *
 * - {@link SET_REFERER_RULE} merges one domain's headers into `chrome.storage.local`;
 * - {@link ensureRefererRule} does the same for a single request (used by `FETCH_REQUEST`);
 * - every mutation regenerates the whole set of dynamic rules inside a serialized queue, so two
 *   concurrent messages can never leave a half-applied, order-dependent rule set.
 *
 * Dynamic rules persist across browser restarts, so {@link reconcileRefererRules} runs on every
 * service-worker start and drops rules whose spec disappeared (e.g. `chrome.storage.local` cleared,
 * or the extension updated to a smaller rule set).
 *
 * Clearing one header is intentionally not supported: a domain either spoofs what you tell it to or
 * owns no rule at all. Use `{ remove: true }` to drop a domain, or `{ clear: true }` for everything.
 */

const RULE_ID_BASE = 9000;
/** Dynamic-rule slots reserved for referer spoofing; keeps us far below the 5000-rule quota. */
export const MAX_REFERER_RULES = 400;

const STORAGE_KEY = "comics8.refererRules";

/** One spoofed domain. At least one of `referer` / `userAgent` is always present. */
export type RefererRuleSpec = {
  /** ASCII hostname, e.g. `manga.example.com`. `requestDomains` also matches sub-domains. */
  domain: string;
  referer?: string;
  userAgent?: string;
};

export type SetRefererRuleMessage = {
  action: "SET_REFERER_RULE";
  /** Domain(s) or full URL(s) whose requests should be rewritten. */
  domains?: string | string[];
  domain?: string;
  url?: string;
  referer?: string | null;
  userAgent?: string | null;
  /** Drop the stored spec instead of merging. */
  remove?: boolean;
  /** Drop every stored spec. */
  clear?: boolean;
};

export type GetRefererRulesMessage = { action: "GET_REFERER_RULES" };

export type RefererRuleResult = {
  action: "SET_REFERER_RULE" | "GET_REFERER_RULES";
  ok: boolean;
  /** Rules that are now installed. */
  rules: RefererRuleSpec[];
  error?: string;
};

/** Everything a reference request can be, so header spoofing applies to `<img>` and `fetch()`. */
const RESOURCE_TYPES: `${chrome.declarativeNetRequest.ResourceType}`[] = [
  "main_frame",
  "sub_frame",
  "stylesheet",
  "script",
  "image",
  "font",
  "object",
  "xmlhttprequest",
  "ping",
  "media",
  "websocket",
  "other",
];

/**
 * Installs the message handlers and reconciles persisted rules with the browser.
 *
 * Returns an unsubscribe function. Must be called once from the service worker's top level.
 */
export function installRefererRuleHandler(): () => void {
  const runtime = chromeApi()?.runtime;
  const onMessage = runtime?.onMessage;

  const listener = (
    message: unknown,
    _sender: chrome.runtime.MessageSender,
    sendResponse: (response: RefererRuleResult) => void,
  ): boolean => {
    if (isSetMessage(message)) {
      void handleSet(message)
        .then(sendResponse)
        .catch((error: unknown) =>
          sendResponse({
            action: "SET_REFERER_RULE",
            ok: false,
            rules: [],
            error: describeError(error),
          }),
        );
      return true;
    }

    if (isGetMessage(message)) {
      void listSpecs()
        .then((rules) => sendResponse({ action: "GET_REFERER_RULES", ok: true, rules }))
        .catch((error: unknown) =>
          sendResponse({
            action: "GET_REFERER_RULES",
            ok: false,
            rules: [],
            error: describeError(error),
          }),
        );
      return true;
    }

    return false;
  };

  const typed = listener as unknown as Parameters<NonNullable<typeof onMessage>["addListener"]>[0];
  onMessage?.addListener(typed);

  // Keep persisted specs and installed rules in sync after restarts and updates.
  const reconcile = (): void => {
    void reconcileRefererRules().catch(() => undefined);
  };
  reconcile();
  runtime?.onInstalled?.addListener(reconcile);
  runtime?.onStartup?.addListener(reconcile);

  return () => {
    onMessage?.removeListener?.(typed);
    runtime?.onInstalled?.removeListener?.(reconcile);
    runtime?.onStartup?.removeListener?.(reconcile);
  };
}

/**
 * Ensures `url`'s host sends `referer` / `userAgent`.
 *
 * Called by the `FETCH_REQUEST` handler when a caller supplies headers that `fetch()` cannot set.
 * Unknown or non-HTTP URLs are ignored rather than failing the request.
 *
 * The common case — a chapter with 200 images all sending the same spoof — must not hit storage or
 * the DNR API 200 times, so the desired state is memoised and only a genuine change mutates rules.
 */
export async function ensureRefererRule(options: {
  url: string;
  referer?: string | null;
  userAgent?: string | null;
}): Promise<void> {
  const domain = hostnameOf(options.url);
  if (!domain) return;

  const referer = normalizeReferer(options.referer);
  const userAgent = normalizeUserAgent(options.userAgent);
  if (!referer && !userAgent) return;

  const known = await ensureKnownSpecs();
  const existing = known.get(domain);
  const next: RefererRuleSpec = {
    domain,
    // An explicit value wins; otherwise keep whatever is already installed for this domain.
    referer: referer ?? existing?.referer,
    userAgent: userAgent ?? existing?.userAgent,
  };
  if (existing && existing.referer === next.referer && existing.userAgent === next.userAgent) {
    return;
  }

  await mutateSpecs((specs) => mergeSpec(specs, next));
}

/** Re-installs every persisted rule and removes stale ones. */
export async function reconcileRefererRules(): Promise<RefererRuleSpec[]> {
  return enqueue(async () => {
    const specs = await readSpecs();
    await applySpecs(specs);
    rememberSpecs(specs);
    return specs;
  });
}

/** Current stored specs (not necessarily installed; see {@link reconcileRefererRules}). */
export async function listRefererRules(): Promise<RefererRuleSpec[]> {
  return listSpecs();
}

/**
 * Drops the in-worker memo of the installed rule set.
 *
 * Only needed when the backing store changes behind the worker's back (tests, or a second context
 * that shares `chrome.storage.local`); normal updates go through {@link mutateSpecs}.
 */
export function resetRefererRuleCache(): void {
  knownSpecs = null;
}

// ------------------------------------------------------------------------------------------------
// message handling
// ------------------------------------------------------------------------------------------------

async function handleSet(message: SetRefererRuleMessage): Promise<RefererRuleResult> {
  if (message.clear === true) {
    const rules = await mutateSpecs(() => []);
    return { action: "SET_REFERER_RULE", ok: true, rules };
  }

  const rawDomains = collectDomains(message);
  const domains = rawDomains
    .map((value) => hostnameOf(value))
    .filter((value): value is string => value !== null);

  if (domains.length === 0) {
    throw new Error("SET_REFERER_RULE requires at least one valid http(s) domain or URL");
  }

  const referer = normalizeReferer(message.referer);
  const userAgent = normalizeUserAgent(message.userAgent);

  if (message.remove === true) {
    const removing = new Set(domains);
    const rules = await mutateSpecs((specs) => specs.filter((spec) => !removing.has(spec.domain)));
    return { action: "SET_REFERER_RULE", ok: true, rules };
  }

  if (!referer && !userAgent) {
    throw new Error("SET_REFERER_RULE requires `referer` and/or `userAgent`");
  }

  const rules = await mutateSpecs((specs) =>
    domains.reduce(
      (acc, domain) => mergeSpec(acc, { domain, referer, userAgent }),
      specs,
    ),
  );
  return { action: "SET_REFERER_RULE", ok: true, rules };
}

function collectDomains(message: SetRefererRuleMessage): string[] {
  const values: string[] = [];
  if (typeof message.domain === "string") values.push(message.domain);
  if (typeof message.url === "string") values.push(message.url);
  if (typeof message.domains === "string") values.push(message.domains);
  if (Array.isArray(message.domains)) values.push(...message.domains);
  return values.filter((value) => typeof value === "string" && value.trim() !== "");
}

function isSetMessage(message: unknown): message is SetRefererRuleMessage {
  return isRecord(message) && message.action === "SET_REFERER_RULE";
}

function isGetMessage(message: unknown): message is GetRefererRulesMessage {
  return isRecord(message) && message.action === "GET_REFERER_RULES";
}

// ------------------------------------------------------------------------------------------------
// state
// ------------------------------------------------------------------------------------------------

/** Serializes read-modify-write cycles over {@link STORAGE_KEY} and the dynamic rule set. */
let queue: Promise<unknown> = Promise.resolve();

function enqueue<T>(task: () => Promise<T>): Promise<T> {
  const run = queue.then(task, task);
  // A failed task must not poison the queue for later callers.
  queue = run.then(
    () => undefined,
    () => undefined,
  );
  return run;
}

async function listSpecs(): Promise<RefererRuleSpec[]> {
  return readSpecs();
}

async function mutateSpecs(
  mutate: (specs: RefererRuleSpec[]) => RefererRuleSpec[],
): Promise<RefererRuleSpec[]> {
  return enqueue(async () => {
    const current = await readSpecs();
    const next = dedupe(mutate(current)).slice(0, MAX_REFERER_RULES);
    await writeSpecs(next);
    await applySpecs(next);
    rememberSpecs(next);
    return next;
  });
}

function mergeSpec(
  specs: RefererRuleSpec[],
  update: RefererRuleSpec,
): RefererRuleSpec[] {
  const index = specs.findIndex((spec) => spec.domain === update.domain);
  if (index === -1) {
    return update.referer || update.userAgent ? [...specs, update] : specs;
  }

  const existing = specs[index];
  const merged: RefererRuleSpec = {
    domain: update.domain,
    // `undefined` means "leave as is"; `null` was normalised away above.
    referer: update.referer ?? existing.referer,
    userAgent: update.userAgent ?? existing.userAgent,
  };

  const next = specs.filter((_, i) => i !== index);
  if (merged.referer || merged.userAgent) {
    // Preserve the original ordering so rule priorities stay stable.
    next.splice(index, 0, merged);
  }
  return next;
}

function dedupe(specs: RefererRuleSpec[]): RefererRuleSpec[] {
  const byDomain = new Map<string, RefererRuleSpec>();
  for (const spec of specs) {
    if (!spec.domain) continue;
    if (!spec.referer && !spec.userAgent) continue;
    byDomain.set(spec.domain, spec);
  }
  return [...byDomain.values()];
}

async function applySpecs(specs: RefererRuleSpec[]): Promise<void> {
  const dnr = chromeApi()?.declarativeNetRequest;
  if (!dnr?.updateDynamicRules) {
    throw new Error(
      "chrome.declarativeNetRequest is unavailable (missing `declarativeNetRequest` permission)",
    );
  }

  const existing = await dnr.getDynamicRules();
  const ours = existing
    .filter((rule) => rule.id >= RULE_ID_BASE && rule.id < RULE_ID_BASE + MAX_REFERER_RULES)
    .map((rule) => rule.id);

  const addRules = specs.map((spec, index) => buildRule(RULE_ID_BASE + index, spec));

  await dnr.updateDynamicRules({ removeRuleIds: ours, addRules });
}

/** Builds one `modifyHeaders` rule. `id` must be within the reserved range. */
export function buildRule(id: number, spec: RefererRuleSpec): chrome.declarativeNetRequest.Rule {
  const requestHeaders: chrome.declarativeNetRequest.ModifyHeaderInfo[] = [];
  if (spec.referer) {
    requestHeaders.push({ header: "Referer", operation: "set", value: spec.referer });
  }
  if (spec.userAgent) {
    requestHeaders.push({ header: "User-Agent", operation: "set", value: spec.userAgent });
  }

  return {
    id,
    priority: 1,
    action: {
      type: "modifyHeaders",
      requestHeaders,
    },
    condition: {
      requestDomains: [spec.domain],
      resourceTypes: RESOURCE_TYPES,
    },
  };
}

// ------------------------------------------------------------------------------------------------
// storage
// ------------------------------------------------------------------------------------------------

async function readSpecs(): Promise<RefererRuleSpec[]> {
  const storage = chromeApi()?.storage?.local;
  if (!storage) return memorySpecs;

  const stored = await storage.get(STORAGE_KEY);
  const value = stored?.[STORAGE_KEY];
  if (!Array.isArray(value)) return [];

  const specs: RefererRuleSpec[] = [];
  for (const entry of value) {
    if (!isRecord(entry) || typeof entry.domain !== "string") continue;
    const domain = hostnameOf(entry.domain);
    if (!domain) continue;
    const referer = normalizeReferer(
      typeof entry.referer === "string" ? entry.referer : undefined,
    );
    const userAgent = normalizeUserAgent(
      typeof entry.userAgent === "string" ? entry.userAgent : undefined,
    );
    if (referer || userAgent) specs.push({ domain, referer, userAgent });
  }
  return dedupe(specs);
}

/**
 * Last state this worker pushed to `chrome.storage.local` / the DNR API.
 *
 * `null` until loaded. It exists purely to keep per-request `ensureRefererRule()` calls off the
 * storage and rules APIs; it is never treated as the source of truth for a mutation (those still
 * re-read storage inside the serialized queue).
 */
let knownSpecs: Map<string, RefererRuleSpec> | null = null;

async function ensureKnownSpecs(): Promise<Map<string, RefererRuleSpec>> {
  if (!knownSpecs) {
    const specs = await readSpecs();
    knownSpecs = new Map(specs.map((spec) => [spec.domain, spec]));
  }
  return knownSpecs;
}

function rememberSpecs(specs: RefererRuleSpec[]): void {
  knownSpecs = new Map(specs.map((spec) => [spec.domain, spec]));
}

async function writeSpecs(specs: RefererRuleSpec[]): Promise<void> {
  const storage = chromeApi()?.storage?.local;
  if (!storage) {
    memorySpecs = specs;
    return;
  }
  await storage.set({ [STORAGE_KEY]: specs });
}

/** Fallback for environments without `chrome.storage` (unit tests). */
let memorySpecs: RefererRuleSpec[] = [];

// ------------------------------------------------------------------------------------------------
// validation
// ------------------------------------------------------------------------------------------------

/** Extracts an ASCII hostname from a domain or URL, or `null` when it is not usable. */
export function hostnameOf(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;

  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    return null;
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  const hostname = url.hostname.toLowerCase().replace(/\.$/, "");
  if (!hostname || hostname.includes(" ")) return null;
  // Reject wildcards and ports that survived parsing (they cannot appear in `requestDomains`).
  if (hostname.includes("*") || hostname.includes(":")) return null;
  return hostname;
}

export function normalizeReferer(value: string | null | undefined): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  if (/[\r\n]/.test(trimmed)) {
    throw new Error("Referer must not contain CR/LF");
  }

  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    throw new Error(`Invalid Referer URL: ${trimmed}`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("Referer must be an http(s) URL");
  }
  return url.toString();
}

export function normalizeUserAgent(value: string | null | undefined): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  if (/[\r\n]/.test(trimmed)) {
    throw new Error("User-Agent must not contain CR/LF");
  }
  if (trimmed.length > 512) {
    throw new Error("User-Agent is unreasonably long");
  }
  return trimmed;
}

// ------------------------------------------------------------------------------------------------
// small utilities
// ------------------------------------------------------------------------------------------------

function chromeApi(): typeof chrome | undefined {
  const candidate = (globalThis as { chrome?: typeof chrome }).chrome;
  return candidate;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function describeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}
