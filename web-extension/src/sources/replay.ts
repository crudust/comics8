/**
 * Replay engine: runs *synchronous* plugin code in a browser.
 *
 * ## Why replay
 *
 * `docs/PLUGIN_GUIDE.md` §1 defines plugins as synchronous: "Network operations (`host.fetch()`,
 * etc.) block until completion and return data directly". A DOM-free extension context cannot block,
 * yet a plugin written against that contract (`const html = host.fetchText({url})`, used as an
 * expression) must run unmodified. So a plugin method is executed repeatedly:
 *
 * - **pass 1** runs against a *recording*. A request that is not in the recording makes the host
 *   return an inert placeholder (`""` / empty result) and records the request in control-flow order.
 *   Execution continues (or the plugin throws downstream with that placeholder), and the return value
 *   of a pass that recorded anything is discarded.
 * - the recorded requests are fetched in parallel.
 * - **pass N** replays them; once a pass needs nothing new, its return value is genuine.
 *
 * A request that yielded no usable response — transport error, or a plugin throwing on a transient
 * status — is re-fetched a bounded number of times ({@link ReplayOptions.maxAttempts}) and only then
 * reported, so `host.fetchText` retrying a 503 behaves like the native host's internal retry.
 *
 * ## Cost model
 *
 * Monolithic requests (fetch a page, parse it) converge in two passes per *dependency chain*. A loop
 * of independent requests — or `host.fetchAll` — is collected in one pass because placeholders do not
 * abort execution. Recordings are cached per `(source, method, args)`, so revisiting a chapter costs
 * no network; a stale recording self-heals by re-baselining on mismatch.
 *
 * ## Limits (documented, not hidden)
 *
 * - `maxPasses` bounds dependency-chain depth; exceeding it fails loudly instead of guessing.
 * - `maxRequests` bounds fan-out per call (the native host has the same concern).
 * - CPU-bound infinite loops cannot be interrupted — browsers have no preemption.
 * - Plugin code must be deterministic: `Math.random()` or clock-dependent branching defeats replay.
 * - A URL containing an unresolved value (`undefined`, `null`, `NaN`, `[object Object]`) is never
 *   fetched; it is recorded as a failure so the next pass re-derives the real URL.
 */

import { describeError } from "../messaging";

export type ReplayRequest = {
  method: string;
  url: string;
  headers: Record<string, string>;
  body?: string;
};

export type ReplayResponse = {
  ok: boolean;
  status: number;
  headers: Record<string, string>;
  body: string;
  error?: string;
};

export type ReplayFetchExec = (request: ReplayRequest) => Promise<ReplayResponse>;

export type ReplayOptions = {
  /** Distinct host requests allowed per call. */
  maxRequests?: number;
  /** Passes allowed per call (bounds dependency-chain depth). */
  maxPasses?: number;
  /** Fetch attempts for a request that produced no usable response. */
  maxAttempts?: number;
  /** Wall-clock budget covering the async legs. */
  deadlineMs?: number;
  /** Parallel requests per batch. */
  concurrency?: number;
  /**
   * Whether a response deserves another attempt. Defaults to the transient statuses the native host
   * retries (`RETRYABLE_STATUS`), so `host.fetch` mimics its internal retry loop.
   */
  isRetryable?: (response: ReplayResponse) => boolean;
};

/** §6.1: transient statuses the native host retries before returning to the plugin. */
export const RETRYABLE_STATUS = new Set([403, 408, 425, 429, 500, 502, 503, 504]);

export const DEFAULT_MAX_PASSES = 8;
export const DEFAULT_MAX_REQUESTS = 512;
export const DEFAULT_MAX_ATTEMPTS = 3;
export const DEFAULT_CONCURRENCY = 6;

/** Result of asking the engine to serve a request during a pass. */
export type ReplayServe =
  | { kind: "response"; response: ReplayResponse }
  | { kind: "pending" };

type RecordingEntry = {
  request: ReplayRequest;
  /** Usable response, or `null` when the request could not be made at all. */
  response: ReplayResponse | null;
  /** Human-readable failure reason for `response === null`. */
  error?: string;
  /** How many times this request has been attempted. */
  attempts: number;
};

type Recording = RecordingEntry[];

type PendingFetch = { request: ReplayRequest; previousAttempts: number };

const PLACEHOLDER_FRAGMENTS = ["undefined", "null", "NaN", "[object Object]"];

export class ReplayEngine {
  private readonly recordings = new Map<string, Recording>();
  private readonly maxRequests: number;
  private readonly maxPasses: number;
  private readonly maxAttempts: number;
  private readonly deadlineMs: number;
  private readonly concurrency: number;
  private readonly isRetryable: (response: ReplayResponse) => boolean;
  private readonly exec: ReplayFetchExec;

  constructor(exec: ReplayFetchExec, options: ReplayOptions = {}) {
    this.exec = exec;
    this.maxRequests = options.maxRequests ?? DEFAULT_MAX_REQUESTS;
    this.maxPasses = options.maxPasses ?? DEFAULT_MAX_PASSES;
    this.maxAttempts = options.maxAttempts ?? DEFAULT_MAX_ATTEMPTS;
    this.deadlineMs = options.deadlineMs ?? 30_000;
    this.concurrency = options.concurrency ?? DEFAULT_CONCURRENCY;
    this.isRetryable = options.isRetryable ?? defaultIsRetryable;
  }

  /**
   * Executes `method` until a pass needs no new host requests.
   *
   * `serve` is handed to the host and called once per request, in plugin control-flow order.
   */
  async run<T>(
    key: string,
    method: (serve: (request: ReplayRequest) => ReplayServe) => T,
  ): Promise<T> {
    try {
      return await this.runPasses(key, method);
    } catch (error) {
      // Never cache a failed outcome: the next call must be able to start from scratch (the site may
      // have fixed itself, the plugin may have been given different data).
      this.recordings.delete(key);
      throw error;
    }
  }

  private async runPasses<T>(
    key: string,
    method: (serve: (request: ReplayRequest) => ReplayServe) => T,
  ): Promise<T> {
    const deadline = Date.now() + this.deadlineMs;
    let recording: Recording = this.recordings.get(key) ?? [];

    for (let pass = 1; pass <= this.maxPasses; pass += 1) {
      this.assertDeadline(deadline, key);

      let cursor = 0;
      let divergedAt: number | null = null;
      const pending: PendingFetch[] = [];

      const serve = (request: ReplayRequest): ReplayServe => {
        const index = cursor;
        const recorded = recording[index];
        cursor += 1;

        if (divergedAt === null && recorded !== undefined && sameRequest(recorded.request, request)) {
          const retryable =
            recorded.response === null ? recorded.error !== unchangingFailure(request) :
              this.isRetryable(recorded.response);

          if (!retryable) {
            if (recorded.response !== null) return { kind: "response", response: recorded.response };
            throw new Error(failureMessage(request, recorded.error));
          }

          // Transient: retry a bounded number of times, then let the plugin see the last response or
          // the failure — exactly what a blocking host does after exhausting its retries.
          if (recorded.attempts < this.maxAttempts) {
            divergedAt = index;
            pending.push({ request, previousAttempts: recorded.attempts });
            return { kind: "pending" };
          }
          if (recorded.response !== null) return { kind: "response", response: recorded.response };
          throw new Error(failureMessage(request, recorded.error));
        }

        // Control flow diverged: nothing after this point in the old recording is trustworthy.
        if (divergedAt === null) divergedAt = index;
        pending.push({ request, previousAttempts: 0 });
        return { kind: "pending" };
      };

      let passError: unknown = null;
      let result: T | undefined;
      try {
        result = method(serve);
      } catch (error) {
        // Expected while a placeholder propagates through the plugin. It only becomes a real failure
        // when the pass recorded nothing that could fill the gap.
        passError = error;
      }

      if (pending.length === 0) {
        if (passError !== null) throw passError;
        // Converged: keep only the prefix this pass actually replayed.
        this.recordings.set(key, recording.slice(0, cursor));
        return result as T;
      }

      const distinct = new Map<string, PendingFetch>();
      for (const entry of pending) {
        const id = requestKey(entry.request);
        const existing = distinct.get(id);
        if (!existing) distinct.set(id, entry);
        else distinct.set(id, { ...existing, previousAttempts: Math.max(existing.previousAttempts, entry.previousAttempts) });
      }
      if (distinct.size > this.maxRequests) {
        throw new Error(
          `source ${key}: ${distinct.size} host requests in one call exceeds the ${this.maxRequests} limit`,
        );
      }

      const resolved = await this.resolveAll([...distinct.values()], deadline, key);

      const extended: Recording = recording.slice(0, divergedAt ?? 0);
      for (const entry of pending) {
        const outcome = resolved.get(requestKey(entry.request));
        extended.push({
          request: entry.request,
          response: outcome?.response ?? null,
          error: outcome?.error,
          attempts: entry.previousAttempts + 1,
        });
      }

      recording = extended;
      this.recordings.set(key, recording);
      void passError;
    }

    throw new Error(
      `source ${key}: host requests did not converge within ${this.maxPasses} passes`,
    );
  }

  /** Forgets every recording (all keys) or just one. */
  invalidate(key?: string): void {
    if (key === undefined) this.recordings.clear();
    else this.recordings.delete(key);
  }

  private async resolveAll(
    requests: PendingFetch[],
    deadline: number,
    key: string,
  ): Promise<Map<string, { response: ReplayResponse | null; error?: string }>> {
    const out = new Map<string, { response: ReplayResponse | null; error?: string }>();
    let cursor = 0;

    const worker = async (): Promise<void> => {
      for (;;) {
        const index = cursor;
        cursor += 1;
        if (index >= requests.length) return;
        this.assertDeadline(deadline, key);
        const { request } = requests[index];
        out.set(requestKey(request), await this.fetchOne(request));
      }
    };

    await Promise.all(Array.from({ length: Math.min(this.concurrency, requests.length) }, worker));
    return out;
  }

  private async fetchOne(
    request: ReplayRequest,
  ): Promise<{ response: ReplayResponse | null; error?: string }> {
    if (!isFetchableUrl(request.url)) {
      return { response: null, error: unchangingFailure(request) };
    }
    try {
      const response = await this.exec(request);
      if (response.error) return { response: null, error: response.error };
      return { response };
    } catch (error) {
      return { response: null, error: describeError(error) };
    }
  }

  private assertDeadline(deadline: number, key: string): void {
    if (Date.now() > deadline) {
      throw new Error(`source ${key}: call timed out after ${this.deadlineMs}ms`);
    }
  }
}

/**
 * Failure reason for a request that will never succeed as issued.
 *
 * Retrying these is pointless — only a later pass (with better inputs) can fix them.
 */
function unchangingFailure(request: ReplayRequest): string {
  return `host request URL is incomplete: ${request.url}`;
}

function failureMessage(request: ReplayRequest, cause?: string): string {
  const endpoint = `${request.method.toUpperCase()} ${request.url}`;
  if (!cause || cause === unchangingFailure(request)) return cause ?? `host request failed (${endpoint})`;
  return `host request failed (${endpoint}): ${cause}`;
}

function isFetchableUrl(url: string): boolean {
  if (!url) return false;
  if (!/^https?:\/\//i.test(url)) return false;
  return !PLACEHOLDER_FRAGMENTS.some((fragment) => url.includes(fragment));
}

function defaultIsRetryable(response: ReplayResponse): boolean {
  return RETRYABLE_STATUS.has(response.status);
}

/** Identity of a request for replay. Header order must not matter. */
export function requestKey(request: ReplayRequest): string {
  const headers = Object.keys(request.headers)
    .sort()
    .map((name) => `${name.toLowerCase()}:${request.headers[name]}`);
  return `${request.method.toUpperCase()} ${request.url}\n${headers.join("\n")}\n${request.body ?? ""}`;
}

function sameRequest(a: ReplayRequest, b: ReplayRequest): boolean {
  if (a.url !== b.url) return false;
  if (a.method.toUpperCase() !== b.method.toUpperCase()) return false;
  if ((a.body ?? "") !== (b.body ?? "")) return false;
  return requestKey(a) === requestKey(b);
}

