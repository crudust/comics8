/**
 * Pure display logic for the Browse and Series surfaces.
 *
 * Everything here is a plain function over plugin data: no DOM, no stores, no Svelte runes. That
 * keeps the two views thin and lets the awkward parts — 1-based vs 0-based pages, "how many
 * episodes is that really", a genre string from a plugin — be tested directly.
 *
 * Owners this mirrors:
 * - `docs/PLUGIN_GUIDE.md` §3 (`ToonItem`, `EpisodeItem`, `PageInfo`) for the input shapes;
 * - `core/src/main/kotlin/model/EpisodeListingTotal.kt` for the rule that an episode total is never
 *   estimated (a real count or nothing);
 * - `core/src/main/kotlin/model/ReaderProgress.kt` for 0-based page indexes in storage.
 */

import type { EpisodeItem, PageInfo, ToonItem } from "../sources/types";

/** Three tags is what fits on a poster card before it starts stealing room from the title. */
export const MAX_CARD_TAGS = 3;
/** The series header has more room than a card, but still clamps. */
export const MAX_HEADER_TAGS = 4;

/**
 * Splits a plugin's free-form genre string into chips.
 *
 * Plugins write genre however they like ("Webcomic, Fantasy (CC BY 4.0)", "액션/판타지", "연재·일상"),
 * so the separators are all of the ones seen in the bundled sources rather than a single character.
 */
export function splitTags(genre: string | null | undefined, limit = MAX_CARD_TAGS): string[] {
  if (!genre) return [];
  return genre
    .split(/[,·|/]/)
    .map((part) => part.trim())
    .filter(Boolean)
    .slice(0, Math.max(0, limit));
}

/**
 * Clamps a plugin's `PageInfo` into 1-based integers that always satisfy `1 <= current <= last`.
 *
 * A plugin that answers `{ currentPage: 0 }`, omits `pageInfo`, or reports a current page beyond the
 * last one would otherwise disable the pager or render "0 / 0".
 */
export function normalisePageInfo(info: PageInfo | undefined, fallback: number): PageInfo {
  const currentPage = Math.max(1, Math.trunc(info?.currentPage ?? fallback));
  const lastPage = Math.max(currentPage, Math.trunc(info?.lastPage ?? currentPage));
  return { currentPage, lastPage };
}

/**
 * Episode count label.
 *
 * Only a listing whose whole result fits on one page is counted exactly. For a paginated listing the
 * label reports the loaded slice and the current page instead: multiplying `lastPage * pageSize`
 * invents a number, and `EpisodeListingTotal.kt` documents why a guessed denominator is worse than
 * no denominator at all (it is displayed as fact and can never be corrected).
 */
export function episodeCountLabel(loaded: number, pageInfo: PageInfo): string {
  if (pageInfo.lastPage <= 1) return `총 ${loaded}화`;
  return `${loaded}화 표시 중 · ${pageInfo.currentPage}/${pageInfo.lastPage} 페이지`;
}

/**
 * Position of an episode inside the work, counted from the oldest chapter.
 *
 * Episode order in a listing is the source's own (newest first for the bundled plugins), so the
 * number is derived by reversing the loaded page. Returns `null` when the number cannot be trusted:
 * the listing is paginated (the page is a slice, not the whole work) or the episode is not on it.
 */
export function episodeNumber(
  episode: EpisodeItem,
  episodes: readonly EpisodeItem[],
  pageInfo: PageInfo,
): number | null {
  if (pageInfo.lastPage > 1) return null;
  const index = episodes.length - 1 - episodes.findIndex((entry) => entry.wrId === episode.wrId);
  if (index < 0 || index >= episodes.length) return null;
  return index + 1;
}

/** What the resume button needs; `pageIndex` is 0-based, exactly like the stored history row. */
export type ResumeTarget = {
  lastWrId: string;
  lastEpisodeTitle: string;
  pageIndex: number;
  totalPages: number;
};

/**
 * "12화 이어보기 (15/45p)".
 *
 * The chapter is labelled by its position in the listing (that is what "12화" means); when the
 * episode is not on the loaded page the stored title is used instead of inventing a number. The page
 * part is 1-based for display while storage keeps 0-based indexes, so a fresh chapter shows no page
 * part at all rather than "(1/45p)".
 */
export function resumeLabel(
  target: ResumeTarget,
  episodes: readonly EpisodeItem[],
  pageInfo: PageInfo,
): string {
  const found = episodes.find((entry) => entry.wrId === target.lastWrId);
  const number = found ? episodeNumber(found, episodes, pageInfo) : null;
  const episodeName = number !== null ? `${number}화` : target.lastEpisodeTitle || '마지막 회차';

  if (target.totalPages > 0) {
    const current = Math.min(target.pageIndex + 1, target.totalPages);
    // Page 1 of N is where a chapter opens; showing "1/45p" would claim progress that is not there.
    if (current > 1) return `${episodeName} 이어보기 (${current}/${target.totalPages}p)`;
  } else if (target.pageIndex > 0) {
    return `${episodeName} 이어보기 (${target.pageIndex + 1}p)`;
  }
  return `${episodeName} 이어보기`;
}

/** "15/45p" for the highlighted episode row, or `null` when there is no page progress to show. */
export function pageProgressLabel(pageIndex: number, totalPages: number): string | null {
  if (totalPages > 0) {
    const current = Math.min(pageIndex + 1, totalPages);
    return current > 1 ? `${current}/${totalPages}p` : null;
  }
  return pageIndex > 0 ? `${pageIndex + 1}p` : null;
}

/** True when a string is an absolute http(s) URL a `target="_blank"` link can safely point at. */
export function isHttpUrl(value: string | null | undefined): boolean {
  return /^https?:\/\//i.test(value ?? "");
}

/**
 * Cover/anchor URL for a series: the series page when it is a real URL, else the source origin.
 * Plugins may leave `href` empty (a listing built from an API), and an empty `<a href>` would
 * navigate away from the reader.
 */
export function originHref(href: string | null | undefined, origin: string | null | undefined): string {
  if (isHttpUrl(href)) return href as string;
  if (isHttpUrl(origin)) return origin as string;
  return "";
}

/** Drops entries a `{#each}` keyed block would reject (missing or repeated `wrId`/`href`). */
export function dedupeEpisodes(list: readonly EpisodeItem[]): EpisodeItem[] {
  const seen = new Set<string>();
  const out: EpisodeItem[] = [];
  for (const episode of list) {
    const key = episode.wrId || episode.href;
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(episode);
  }
  return out;
}

/** Identity of a listing card. `entryEpisodeId` makes one-shot titles distinct entries. */
export function seriesCardKey(item: ToonItem): string {
  return `${item.id}\u0000${item.entryEpisodeId ?? ""}`;
}

/** Drops repeated cards from a listing page (some sources paginate sloppily). */
export function dedupeSeries(list: readonly ToonItem[]): ToonItem[] {
  const seen = new Set<string>();
  const out: ToonItem[] = [];
  for (const item of list) {
    const key = seriesCardKey(item);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

/** Reverses a listing page without mutating the reactive array the view renders. */
export function reverseEpisodes(list: readonly EpisodeItem[]): EpisodeItem[] {
  return [...list].reverse();
}
