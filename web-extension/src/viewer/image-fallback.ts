export const AUTO_MAX_FALLBACKS = 3;
export const MANUAL_MAX_FALLBACKS = 12;
export const HOST_PREFERENCE_TTL_MS = 120_000;
export const SOFT_FAILURE_PROMOTE_THRESHOLD = 2;

type PreferredHostEntry = {
  targetHost: string;
  expiresAt: number;
};

type PageFallbackState = {
  tried: Set<string>;
  autoAttempts: number;
  manualRetries: number;
};

function hostOf(url: string): string | null {
  try {
    const host = new URL(url).hostname.toLowerCase();
    return host || null;
  } catch {
    return null;
  }
}

export class ImageFallbackController {
  readonly #clock: () => number;
  readonly #preferredHosts = new Map<string, PreferredHostEntry>();
  readonly #softRecoveryCounts = new Map<string, number>();
  readonly #pages = new Map<number, PageFallbackState>();

  constructor(clock: () => number = () => Date.now()) {
    this.#clock = clock;
  }

  /** Clears per-page attempt tracking for a new episode while preserving session host affinity. */
  resetEpisode(): void {
    this.#pages.clear();
  }

  hasPreferredHostFor(originalUrl: string): boolean {
    const origHost = hostOf(originalUrl);
    if (!origHost) return false;
    return this.#activePreferredHost(origHost) !== null;
  }

  preferredCandidate(originalUrl: string, fallbacks: readonly string[]): string | null {
    const origHost = hostOf(originalUrl);
    if (!origHost) return null;
    const pref = this.#activePreferredHost(origHost);
    if (!pref) return null;
    return fallbacks.find((candidate) => hostOf(candidate) === pref.targetHost) ?? null;
  }

  nextUrl(
    index: number,
    originalUrl: string,
    failedUrl: string,
    fallbacks: readonly string[],
    mode: "auto" | "manual",
  ): string | null {
    let state = this.#pages.get(index);
    if (!state) {
      state = { tried: new Set<string>(), autoAttempts: 0, manualRetries: 0 };
      this.#pages.set(index, state);
    }

    if (failedUrl) state.tried.add(failedUrl);

    const origHost = hostOf(originalUrl);
    const failedHost = hostOf(failedUrl);
    const pref = origHost ? this.#activePreferredHost(origHost) : null;
    if (origHost && pref && failedHost === pref.targetHost) {
      // Self-heal: clear broken sticky host so subsequent attempts fall back to origin/mirrors.
      this.#preferredHosts.delete(origHost);
    }

    const ordered = this.#buildOrderedPool(originalUrl, fallbacks);
    if (mode === "auto") {
      if (state.autoAttempts >= AUTO_MAX_FALLBACKS) return null;
      const next = ordered.find((candidate) => candidate !== failedUrl && !state.tried.has(candidate)) ?? null;
      if (!next) return null;
      state.autoAttempts += 1;
      state.tried.add(next);
      return next;
    }

    const cappedPool = ordered.slice(0, MANUAL_MAX_FALLBACKS);
    if (cappedPool.length === 0) return null;

    let next = cappedPool.find((candidate) => candidate !== failedUrl && !state.tried.has(candidate)) ?? null;
    if (!next) {
      state.tried.clear();
      if (failedUrl) state.tried.add(failedUrl);
      next = cappedPool.find((candidate) => candidate !== failedUrl) ?? null;
    }
    if (!next) return null;
    state.manualRetries += 1;
    state.tried.add(next);
    return next;
  }

  recordSuccess(originalUrl: string, loadedUrl: string): void {
    const origHost = hostOf(originalUrl);
    const loadedHost = hostOf(loadedUrl);
    if (!origHost || !loadedHost) return;

    if (origHost === loadedHost) {
      const prefix = `${origHost}->`;
      for (const key of this.#softRecoveryCounts.keys()) {
        if (key.startsWith(prefix)) this.#softRecoveryCounts.delete(key);
      }
      return;
    }

    const key = `${origHost}->${loadedHost}`;
    const count = (this.#softRecoveryCounts.get(key) ?? 0) + 1;
    if (count >= SOFT_FAILURE_PROMOTE_THRESHOLD) {
      this.#preferredHosts.set(origHost, {
        targetHost: loadedHost,
        expiresAt: this.#clock() + HOST_PREFERENCE_TTL_MS,
      });
      this.#softRecoveryCounts.delete(key);
    } else {
      this.#softRecoveryCounts.set(key, count);
    }
  }

  #activePreferredHost(origHost: string): PreferredHostEntry | null {
    const entry = this.#preferredHosts.get(origHost);
    if (!entry) return null;
    if (entry.expiresAt <= this.#clock()) {
      this.#preferredHosts.delete(origHost);
      return null;
    }
    return entry;
  }

  #buildOrderedPool(originalUrl: string, fallbacks: readonly string[]): string[] {
    const seen = new Set<string>();
    const out: string[] = [];
    const prefCandidate = this.preferredCandidate(originalUrl, fallbacks);
    if (prefCandidate && !seen.has(prefCandidate)) {
      seen.add(prefCandidate);
      out.push(prefCandidate);
    }
    if (originalUrl && !seen.has(originalUrl)) {
      seen.add(originalUrl);
      out.push(originalUrl);
    }
    for (const candidate of fallbacks) {
      if (candidate && !seen.has(candidate)) {
        seen.add(candidate);
        out.push(candidate);
      }
    }
    return out;
  }
}
