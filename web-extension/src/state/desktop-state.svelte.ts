/**
 * Desktop parity store: a 1:1 Svelte 5 port of the Compose Desktop read model.
 *
 * Source of truth on the Kotlin side:
 * - `desktop/src/main/kotlin/ui/DesktopUiState.kt` → the reactive fields below.
 * - `desktop/src/main/kotlin/ui/DesktopViewModel.kt` → the navigation/loading actions below.
 * - `core/src/main/kotlin/model/ReaderDomain.kt` / `EpisodeSortOrder.kt` → the pure rules
 *   (`nextEpisode`, `prevEpisode`, `sortedWithOrder`) ported at the bottom of this file, so the
 *   web shell makes exactly the same decisions as the desktop app instead of re-deriving them.
 *
 * Mapping notes (deliberate, not accidental):
 * - `BrowseTab` is flattened to {@link DesktopBrowseTab}. A remote catalog keeps its
 *   `catalogId`/`paginated` flags; `BrowseTab.Favorite` becomes `kind: "favorite"`, which the web
 *   store serves straight out of `favoritesStore` (desktop reads the repository instead).
 * - `ToonItem.sourceId` does not exist in `sources/types.ts` because a plugin never sees it: the
 *   caller knows which source produced the card. Every item entering this store is therefore
 *   stamped with the owning source id ({@link DesktopToonItem}), which is the web equivalent of
 *   `ToonItem.sourceId` and the identity half of `WorkId.storageKey()` (`sourceId:toonId`).
 * - `EpisodeItem.lastReadPage` / `isRead` are the same projection: the plugin model is pure, the
 *   read model carries the reader progress the desktop `EpisodeItem` holds.
 * - `catalogTotals` is the web twin of the desktop `toon_catalog` table. It is only ever written
 *   from a real count (`RepositoryTransforms.countEpisodeListing`) or from a stored row whose
 *   listing shape still holds (`RepositoryTransforms.totalFromPage`), and it is persisted through
 *   `data/catalog-store.svelte.ts` (`chrome.storage.local`, key `comics8:catalogs`). A paginated
 *   listing is counted page by page, never estimated from `lastPage * pageSize`.
 * - `fetchEpisodeMetadata` is the port of `DesktopToonRepository.fetchEpisodeMetadata`: one page for
 *   a single-page listing, one tail-page read when a stored count still describes the listing, and
 *   a full sweep only when the work was never counted.
 * - `syncEpisodeMetadata` is step 1 of `DesktopToonRepository.syncEpisodeCounts`: the server-first
 *   batch query (`POST /catalog/batch`) that fills the totals another device already counted. It
 *   runs in the background after every listing load, so a browse card prints "104화" / "52/64"
 *   immediately instead of waiting for the series to be opened.
 * - `totalEpisodesByWork` is the number the series header prints. Two sources feed it, in this
 *   order of authority: a total the plugin itself reported (`total` / `totalItems` / `itemCount` /
 *   `totalEpisodes`), then the counted row in `catalogTotals`. `totalEpisodesOf(workKey)` answers
 *   with `null` when nothing is known, so an uncounted work shows no badge instead of a guess.
 * - `installedSources` is `repository.installedSources()`: the registry list filtered by the stored
 *   `SourcePrefs.INSTALLED_KEY` ids and ordered by them (`local` first, storage, then online). The
 *   source manager pane drives that order and the per-source switch, so the read model — not the
 *   pane — owns `openSourceManager` / `toggleSourceEnabled` / `reorderSources` / `uninstallSource`.
 * - `seriesHistory` is a {@link HistoryRecord} (the web history row) instead of the desktop
 *   `ReadHistoryRecord`; both describe "where the user stopped in this series".
 *
 * The store owns a {@link SourceRegistry} because a listing is meaningless without the plugin that
 * produced it; pass a shared one through {@link DesktopStoreOptions.registry} when the shell already
 * has one (same reason `BrowseView`/`SeriesView` accept a registry prop).
 */

import {
  catalogStore,
  describesListingShape,
  isValidEpisodeListingTotal,
  type EpisodeListingTotal,
} from "../data/catalog-store.svelte";
import {
  DownloadStore,
  EMPTY_DOWNLOAD_PROGRESS,
  downloadedWorkKey,
  downloadsStore,
  type DownloadProgressState,
  type DownloadedToonSummary,
} from "../data/downloads-store.svelte";
import { favoritesStore, type FavoriteRecord } from "../data/favorites-store.svelte";
import { historyStore, type HistoryRecord } from "../data/history-store.svelte";
import { backgroundFetch } from "../background-fetch";
import { SourceRegistry } from "../sources/registry";
import type { AnyLoadedSource, SourceSummary } from "../sources/registry";
import {
  DEFAULT_SERVER_URL,
  SyncClient,
  type PullResult,
  type SyncCatalogRow,
  type SyncHistory,
  type SyncStatus,
} from "../sync/sync-client";
import { buildSyncUpdates, mergeRemoteHistory } from "../sync/tasks";
import type {
  EpisodeItem,
  ListingResult,
  PageInfo,
  SearchSuggestion,
  ToonItem,
} from "../sources/types";

// ---------------------------------------------------------------------------------------------
// ui state enums (mirroring `com.comics8.core.model`)
// ---------------------------------------------------------------------------------------------

/** `com.comics8.desktop.ui.Screen`, narrowed to the screens the web shell routes. */
export type Screen =
  | "Browse"
  | "Series"
  | "Reader"
  | "History"
  | "Downloads"
  | "Settings"
  | "SourceManager";

/** `com.comics8.core.model.EpisodeSortOrder` (the Kotlin keys are `default`, `name_asc`, …). */
export type EpisodeSortOrder = "DEFAULT" | "NAME_ASC" | "NAME_DESC" | "DATE_DESC" | "DATE_ASC";

/** `com.comics8.core.model.ViewMode`. */
export type ViewMode = "SCROLL" | "PAGE" | "DUAL";

/** `com.comics8.core.model.ReadDirection`. */
export type ReadDirection = "RIGHT_TO_LEFT" | "LEFT_TO_RIGHT";

/** `com.comics8.core.model.SplitMode`. */
export type SplitMode = "FIT" | "SLICE";

/**
 * `com.comics8.desktop.ui.settings.SettingsCategory` — which sub-pane the settings screen shows.
 *
 * `null` is the hub (`SettingsPane` renders the five category cards); every other value routes to
 * one `*SettingsPane.kt` sub-screen. Kept as a string union so a template can compare directly.
 */
export type SettingsCategory =
  | "GENERAL"
  | "READER"
  | "SYNC_BACKUP"
  | "NETWORK_DOWNLOAD"
  | "ABOUT";

/** `com.comics8.core.i18n.AppLanguage` — `AUTO` follows the system locale. */
export type AppLanguage = "AUTO" | "KO" | "EN" | "JA" | "ZH_CN" | "ZH_TW";

/** `com.comics8.core.model.ProxyType`. */
export type ProxyType = "DIRECT" | "SERVER" | "CUSTOM_HTTP" | "CUSTOM_SOCKS";

/** `com.comics8.core.model.CustomProxyConfig`. */
export type CustomProxyConfig = {
  host: string;
  port: number;
  username: string;
  password: string;
};

/** `com.comics8.core.model.NetworkSettings`. */
export type NetworkSettings = {
  proxyType: ProxyType;
  customProxy: CustomProxyConfig;
};
export const SETTINGS_CATEGORIES: readonly SettingsCategory[] = [
  "GENERAL",
  "READER",
  "SYNC_BACKUP",
  "NETWORK_DOWNLOAD",
  "ABOUT",
];
export const APP_LANGUAGES: readonly AppLanguage[] = [
  "AUTO",
  "KO",
  "EN",
  "JA",
  "ZH_CN",
  "ZH_TW",
];
export const PROXY_TYPES: readonly ProxyType[] = [
  "DIRECT",
  "SERVER",
  "CUSTOM_HTTP",
  "CUSTOM_SOCKS",
];
export const EMPTY_NETWORK_SETTINGS: NetworkSettings = {
  proxyType: "DIRECT",
  customProxy: { host: "", port: 1080, username: "", password: "" },
};

export const EPISODE_SORT_ORDERS: readonly EpisodeSortOrder[] = [
  "DEFAULT",
  "NAME_ASC",
  "NAME_DESC",
  "DATE_DESC",
  "DATE_ASC",
];
export const VIEW_MODES: readonly ViewMode[] = ["SCROLL", "PAGE", "DUAL"];
export const READ_DIRECTIONS: readonly ReadDirection[] = ["RIGHT_TO_LEFT", "LEFT_TO_RIGHT"];
export const SPLIT_MODES: readonly SplitMode[] = ["FIT", "SLICE"];

/** One catalog tab; `BrowseTab.Remote` when `kind === "catalog"`, `BrowseTab.Favorite` otherwise. */
export type DesktopBrowseTab =
  | {
      kind: "catalog";
      /** Stable identity: `${sourceId}:${catalogId}`. */
      id: string;
      sourceId: string;
      catalogId: string;
      label: string;
      paginated: boolean;
    }
  | {
      kind: "favorite";
      id: string;
      sourceId: string;
      catalogId: "FAVORITE";
      label: string;
      paginated: false;
    };

/**
 * `com.comics8.core.model.EpisodeListingTotal`, reduced to what the web read model needs.
 *
 * The persisted row adds `updatedAt` (`EpisodeListingTotal` in `data/catalog-store.svelte.ts`),
 * which is storage metadata rather than a rendering input, so it does not leak into the `$state`
 * map the views diff against.
 */
export type CatalogTotal = {
  count: number;
  lastPage: number;
  pageSize: number;
  delta: number;
};

/** Drops the storage metadata so a persisted row can be mirrored into {@link DesktopStore.catalogTotals}. */
export function toCatalogTotal(total: EpisodeListingTotal): CatalogTotal {
  return {
    count: total.count,
    lastPage: total.lastPage,
    pageSize: total.pageSize,
    delta: total.delta,
  };
}

/** Every persisted row as `CatalogTotal`s, keyed by `WorkId.storageKey()`. */
function mapCatalogTotals(
  stored: Record<string, EpisodeListingTotal>,
): Record<string, CatalogTotal> {
  const out: Record<string, CatalogTotal> = {};
  for (const [key, total] of Object.entries(stored)) {
    if (isValidEpisodeListingTotal(total)) out[key] = toCatalogTotal(total);
  }
  return out;
}

/** Per-series viewer override, the web twin of the desktop `ReaderSettingRecord`. */
export type DesktopReaderSetting = {
  viewMode: ViewMode;
  readDirection: ReadDirection;
  splitMode: SplitMode;
};

/** A `ToonItem` stamped with the source that produced it (web stand-in for `ToonItem.sourceId`). */
export type DesktopToonItem = ToonItem & {
  sourceId?: string;
  isFavorite?: boolean;
  readProgress?: string | null;
};

/** An `EpisodeItem` plus the read state the desktop model carries on the same type. */
export type DesktopEpisodeItem = EpisodeItem & {
  mtime?: number | null;
  isRead?: boolean;
  readAt?: number | null;
  /** Persisted reader page, sign-encoded exactly like `ReaderProgress.encodePage`. */
  lastReadPage?: number;
};

// ---------------------------------------------------------------------------------------------
// pure port of ReaderDomain episode navigation
// ---------------------------------------------------------------------------------------------

export type EpisodeNavigation =
  | { kind: "in-current-page"; index: number }
  | { kind: "load-page"; page: number; edge: "FIRST" | "LAST" }
  | { kind: "none" };

const NO_NAVIGATION: EpisodeNavigation = { kind: "none" };

/** `EpisodeSortOrder.isAscending`. Ascending means "1화 → 최신화" inside the loaded page. */
export function isAscendingOrder(order: EpisodeSortOrder): boolean {
  return order === "NAME_ASC" || order === "DATE_ASC";
}

/** `ReaderDomain.nextEpisode`: the next chapter, a neighbouring episode page, or nothing. */
export function nextEpisodeNavigation(
  currentIndex: number,
  itemCount: number,
  currentPage: number,
  sortOrder: EpisodeSortOrder = "DEFAULT",
): EpisodeNavigation {
  if (currentIndex < 0 || itemCount <= 0) return NO_NAVIGATION;
  if (isAscendingOrder(sortOrder)) {
    return currentIndex < itemCount - 1
      ? { kind: "in-current-page", index: currentIndex + 1 }
      : NO_NAVIGATION;
  }
  // Newest-first: index 0 is the newest, so "next" walks towards the older episodes.
  if (currentIndex >= 1) return { kind: "in-current-page", index: currentIndex - 1 };
  if (currentIndex === 0 && currentPage > 1) {
    return { kind: "load-page", page: currentPage - 1, edge: "LAST" };
  }
  return NO_NAVIGATION;
}

/** `ReaderDomain.prevEpisode`: the previous chapter, a neighbouring episode page, or nothing. */
export function prevEpisodeNavigation(
  currentIndex: number,
  itemCount: number,
  currentPage: number,
  lastPage: number,
  sortOrder: EpisodeSortOrder = "DEFAULT",
): EpisodeNavigation {
  if (currentIndex < 0 || itemCount <= 0) return NO_NAVIGATION;
  if (isAscendingOrder(sortOrder)) {
    return currentIndex >= 1
      ? { kind: "in-current-page", index: currentIndex - 1 }
      : NO_NAVIGATION;
  }
  if (currentIndex < itemCount - 1) return { kind: "in-current-page", index: currentIndex + 1 };
  if (currentIndex === itemCount - 1 && currentPage < lastPage) {
    return { kind: "load-page", page: currentPage + 1, edge: "FIRST" };
  }
  return NO_NAVIGATION;
}

/** `ReaderDomain.clampPage`. */
export function clampPage(page: number, lastPage: number): number {
  const safeLast = Math.max(1, Math.trunc(lastPage));
  return Math.min(Math.max(Math.trunc(page), 1), safeLast);
}

/** `ReaderProgress.isCompleted(persistedPage)` / `decodePage` / `startPageOnOpen`. */
export function isCompletedPage(persistedPage: number): boolean {
  return persistedPage < 0;
}

export function decodeReaderPage(persistedPage: number): number {
  return persistedPage < 0
    ? -persistedPage - 1
    : Math.max(0, Math.trunc(persistedPage));
}

export function startPageOnOpen(persistedPage: number): number {
  return isCompletedPage(persistedPage) ? 0 : decodeReaderPage(persistedPage);
}

// ---------------------------------------------------------------------------------------------
// progress badges (`ProgressDisplayMode`) and episode position (`ReaderDomain.episodePosition`)
// ---------------------------------------------------------------------------------------------

/**
 * `com.comics8.core.model.ProgressDisplayMode`, narrowed to the two modes a JS pack can declare
 * (`ProgressDisplay` in `sources/types.ts`) plus the `local` / `network-` default.
 */
export type ProgressDisplayMode = "LATEST_EPISODE" | "READ_COUNT";

/** `ProgressDisplayMode.defaultFor(sourceId)`: storage sources count chapters, remote ones count up. */
export function defaultProgressDisplayMode(sourceId: string): ProgressDisplayMode {
  return sourceId === LOCAL_SOURCE || sourceId.startsWith("network-")
    ? "READ_COUNT"
    : "LATEST_EPISODE";
}

/** `EpisodeListingTotal.isValid`: an uncounted work has no denominator, so it has no badge. */
function isCountedTotal(total: CatalogTotal | undefined): total is CatalogTotal {
  return isValidEpisodeListingTotal(total);
}

/**
 * One `SyncCatalogRow` as a storable total, or `null` when it does not describe a counted listing.
 *
 * The server only emits rows that carry a listing shape (`emit_catalog`), but a row from an older
 * client can still arrive without one; `EpisodeListingTotal.isValid` then rejects it instead of the
 * badge adopting a number that was never counted end to end.
 */
function catalogTotalFromRow(row: SyncCatalogRow): EpisodeListingTotal | null {
  const count = Math.trunc(Number(row.totalEpisodes ?? 0));
  if (!Number.isFinite(count) || count <= 0) return null;
  // The server always emits the whole shape (`emit_catalog`); the fallbacks only cover a row from
  // an older client. A row whose shape is present but impossible (`0` pages) stays invalid and is
  // dropped, exactly like `SyncCatalogWire.total()` + `fromStored`.
  const total: EpisodeListingTotal = {
    count,
    lastPage: Math.trunc(Number(row.lastPage ?? 1)),
    pageSize: Math.trunc(Number(row.pageSize ?? 100)),
    delta: Math.trunc(Number(row.delta ?? 0)) || 0,
    updatedAt:
      typeof row.updatedAt === "number" && Number.isFinite(row.updatedAt)
        ? Math.trunc(row.updatedAt)
        : undefined,
  };
  return isValidEpisodeListingTotal(total) ? total : null;
}

/**
 * `ProgressDisplayMode.format(lastReadOrder, total, readCount)`.
 *
 * `null` means "render nothing": `EpisodeListingTotal.kt` is explicit that a number derived from
 * `lastPage * pageSize` is a guess, so an uncounted work shows no badge instead of a wrong one.
 */
export function formatProgressBadge(
  mode: ProgressDisplayMode,
  lastReadOrder: number,
  total: CatalogTotal | undefined,
  readCount: number,
): string | null {
  if (!isCountedTotal(total)) return null;
  if (mode === "READ_COUNT") {
    return readCount > 0 ? `${readCount}/${total.count}` : `${total.count}화`;
  }
  return lastReadOrder > 0 ? `${lastReadOrder}/${total.count}` : `${total.count}화`;
}

// ---------------------------------------------------------------------------------------------
// listing totals (`core/src/main/kotlin/model/RepositoryTransforms.kt`)
// ---------------------------------------------------------------------------------------------

/** One loaded episode page, in the shape `RepositoryTransforms` consumes. */
export type EpisodePageShape = {
  items: readonly unknown[];
  currentPage: number;
  lastPage: number;
};

/**
 * A page the caller already fetched (`DesktopToonRepository.fetchEpisodeMetadata`'s `knownPage`).
 *
 * `reported` carries the total the plugin stated on that page, so a source that knows its own
 * episode count never has to be swept.
 */
export type KnownEpisodePage = EpisodePageShape & {
  items: readonly EpisodeItem[];
  reported?: number | null;
};

/** `RepositoryTransforms.EpisodeMetadata` — the counted total plus a thumbnail the listing supplied. */
export type EpisodeMetadata = {
  /** `null` until the listing has been counted end to end. */
  total: EpisodeListingTotal | null;
  thumbUrl: string | null;
};

/** First non-blank episode thumbnail of a page (the desktop's `items.firstOrNull()?.thumbUrl`). */
function firstThumb(items: readonly EpisodeItem[]): string | null {
  for (const item of items) {
    const thumb = (item.thumbUrl ?? "").trim();
    if (thumb !== "") return thumb;
  }
  return null;
}

/**
 * `RepositoryTransforms.totalFromPage`.
 *
 * Recomputes the total from a freshly loaded tail page using the correction learned by
 * {@link countEpisodeListing}. When the listing has only one page, the page itself is the entire
 * listing and produces a verified total directly, without a prior stored shape.
 *
 * Returns `null` when the page is not the listing's tail, or when its shape no longer matches the
 * count: both mean the correction describes a different listing and it has to be recounted.
 */
export function totalFromPage(
  page: EpisodePageShape,
  pageSize: number,
  known: EpisodeListingTotal | null | undefined,
): EpisodeListingTotal | null {
  const lastPage = Math.max(1, Math.trunc(page.lastPage));
  const safePageSize = Math.max(1, Math.trunc(pageSize));
  if (clampPage(page.currentPage, lastPage) !== lastPage) return null;
  const items = page.items.length;
  if (lastPage === 1) {
    const single: EpisodeListingTotal = {
      count: items,
      lastPage: 1,
      pageSize: safePageSize,
      delta: 0,
    };
    return isCountedTotal(single) ? single : null;
  }
  if (!isCountedTotal(known ?? undefined) || !describesListingShape(known, lastPage, safePageSize)) {
    return null;
  }
  const total: EpisodeListingTotal = {
    count: Math.max(
      0,
      (lastPage - 1) * safePageSize + items + Math.trunc((known as EpisodeListingTotal).delta),
    ),
    lastPage,
    pageSize: safePageSize,
    delta: Math.trunc((known as EpisodeListingTotal).delta),
  };
  return isCountedTotal(total) ? total : null;
}

/**
 * `RepositoryTransforms.countEpisodeListing`.
 *
 * Counts every page of an episode listing. This is the only way a total is ever produced from
 * scratch.
 *
 * Returns `null` when any page cannot be read (or a middle page is unexpectedly empty): a partial
 * sweep must never be turned into a total, because the missing pages are exactly the ones that
 * would be guessed.
 */
export async function countEpisodeListing(
  pageSize: number,
  loadPage: (page: number) => Promise<EpisodePageShape> | EpisodePageShape,
): Promise<EpisodeListingTotal | null> {
  const safePageSize = Math.max(1, Math.trunc(pageSize));
  const first = await loadPage(1);
  const lastPage = Math.max(1, Math.trunc(first.lastPage));
  if (first.items.length === 0) return null;

  let counted = first.items.length;
  let tailItems = lastPage === 1 ? first.items.length : 0;
  for (let page = 2; page <= lastPage; page += 1) {
    let loaded: EpisodePageShape;
    try {
      loaded = await loadPage(page);
    } catch {
      return null;
    }
    if (loaded.items.length === 0) return null;
    counted += loaded.items.length;
    if (page === lastPage) tailItems = loaded.items.length;
  }
  // `naive` assumes every page but the tail is full; anything shorter (short middle pages) lands
  // in `delta`, while the tail stays a live value so added/removed newest episodes still move the
  // total even when the page count does not change.
  const naive = (lastPage - 1) * safePageSize + tailItems;
  return {
    count: counted,
    lastPage,
    pageSize: safePageSize,
    delta: counted - naive,
  };
}

/** What {@link episodePosition} can say about one chapter; both halves may be unknown. */
export type EpisodePosition = {  /** 1-based position counted from the oldest chapter, or `null` when it cannot be trusted. */
  readOrder: number | null;
  /** Index of the next (newer) chapter inside the loaded page, or `null` at the newest edge. */
  nextEpisodeIndex: number | null;
};

/**
 * `ReaderDomain.episodePosition`.
 *
 * Only a counted total may bound the order: clamping against a guess truncates how far the user
 * actually read, so an uncounted work on a partially loaded page simply has no order.
 */
export function episodePosition(
  episodeIds: readonly string[],
  currentEpisodeId: string,
  currentPage: number,
  lastPage: number,
  knownTotal: CatalogTotal | undefined,
  pageSize = 100,
): EpisodePosition {
  const safeLastPage = Math.max(1, Math.trunc(lastPage));
  const safeCurrentPage = clampPage(currentPage, safeLastPage);
  const itemCount = episodeIds.length;
  const currentIndex = episodeIds.indexOf(currentEpisodeId);
  const safePageSize = Math.max(1, Math.trunc(pageSize));
  const total = isCountedTotal(knownTotal) ? knownTotal : null;

  const readOrder =
    currentIndex >= 0 && itemCount > 0
      ? safeCurrentPage === safeLastPage
        ? Math.max(1, itemCount - currentIndex)
        : total !== null
          ? Math.min(
              Math.max(1, total.count - ((safeCurrentPage - 1) * safePageSize + currentIndex)),
              total.count,
            )
          : null
      : null;

  return {
    readOrder,
    nextEpisodeIndex:
      currentIndex >= 1 && currentIndex < itemCount ? currentIndex - 1 : null,
  };
}

// ---------------------------------------------------------------------------------------------
// pure port of EpisodeSortOrder.sortedWithOrder / NaturalSort / UpdateDates.parseScore
// ---------------------------------------------------------------------------------------------

function isDigit(value: string): boolean {
  return value >= "0" && value <= "9";
}

/**
 * `core/src/main/kotlin/source/local/NaturalSort.kt`: digit runs compare numerically (`1` == `01`,
 * shorter raw run first), everything else compares case-insensitively, original string last.
 */
export function naturalCompare(a: string | null | undefined, b: string | null | undefined): number {
  const left = a ?? "";
  const right = b ?? "";
  if (left === right) return 0;

  let ia = 0;
  let ib = 0;
  const lenA = left.length;
  const lenB = right.length;

  while (ia < lenA && ib < lenB) {
    const digitA = isDigit(left[ia] as string);
    const startA = ia;
    while (ia < lenA && isDigit(left[ia] as string) === digitA) ia += 1;
    const endA = ia;

    const digitB = isDigit(right[ib] as string);
    const startB = ib;
    while (ib < lenB && isDigit(right[ib] as string) === digitB) ib += 1;
    const endB = ib;

    if (digitA && digitB) {
      let nonZeroA = startA;
      while (nonZeroA < endA && left[nonZeroA] === "0") nonZeroA += 1;
      let nonZeroB = startB;
      while (nonZeroB < endB && right[nonZeroB] === "0") nonZeroB += 1;

      const numLenA = endA - nonZeroA;
      const numLenB = endB - nonZeroB;
      if (numLenA !== numLenB) return numLenA - numLenB;

      for (let k = 0; k < numLenA; k += 1) {
        const ca = left[nonZeroA + k] as string;
        const cb = right[nonZeroB + k] as string;
        if (ca !== cb) return ca.charCodeAt(0) - cb.charCodeAt(0);
      }

      const rawLenA = endA - startA;
      const rawLenB = endB - startB;
      if (rawLenA !== rawLenB) return rawLenA - rawLenB;
    } else {
      const tokenLenA = endA - startA;
      const tokenLenB = endB - startB;
      const shared = Math.min(tokenLenA, tokenLenB);
      for (let k = 0; k < shared; k += 1) {
        const ca = left[startA + k] as string;
        const cb = right[startB + k] as string;
        if (ca === cb) continue;
        const upperA = ca.toUpperCase();
        const upperB = cb.toUpperCase();
        if (upperA !== upperB) return upperA < upperB ? -1 : 1;
        const lowerA = ca.toLowerCase();
        const lowerB = cb.toLowerCase();
        if (lowerA !== lowerB) return lowerA < lowerB ? -1 : 1;
      }
      if (tokenLenA !== tokenLenB) return tokenLenA - tokenLenB;
    }
  }

  const remainingA = countRemainingTokens(left, ia);
  const remainingB = countRemainingTokens(right, ib);
  if (remainingA !== remainingB) return remainingA - remainingB;

  return left < right ? -1 : left > right ? 1 : 0;
}

function countRemainingTokens(value: string, from: number): number {
  if (from >= value.length) return 0;
  let count = 0;
  let i = from;
  while (i < value.length) {
    const digit = isDigit(value[i] as string);
    count += 1;
    while (i < value.length && isDigit(value[i] as string) === digit) i += 1;
  }
  return count;
}

/**
 * `UpdateDates.parseScore`: listings expose `MM.DD`, episode pages `YY.MM.DD`, so a plain string
 * comparison is wrong. Two-part dates get the year inferred from `today`.
 */
export function parseUpdateScore(
  value: string | null | undefined,
  today: Date = new Date(),
): number {
  if (typeof value !== "string") return 0;
  const trimmed = value.trim();
  if (trimmed === "") return 0;
  const parts = trimmed.split(".");
  const toInt = (raw: string | undefined): number => {
    const number = Number.parseInt(raw ?? "", 10);
    return Number.isFinite(number) ? number : Number.NaN;
  };
  if (parts.length === 3) {
    const yy = toInt(parts[0]);
    const mm = toInt(parts[1]);
    const dd = toInt(parts[2]);
    if (!Number.isFinite(yy) || !Number.isFinite(mm) || !Number.isFinite(dd)) return 0;
    return yy * 10000 + mm * 100 + dd;
  }
  if (parts.length === 2) {
    const mm = toInt(parts[0]);
    const dd = toInt(parts[1]);
    if (!Number.isFinite(mm) || !Number.isFinite(dd)) return 0;
    const thisYear = today.getFullYear();
    const candidate = new Date(thisYear, mm - 1, dd);
    const valid =
      Number.isFinite(candidate.getTime()) &&
      candidate.getMonth() === mm - 1 &&
      candidate.getDate() === dd;
    if (!valid) return (thisYear % 100) * 10000 + mm * 100 + dd;
    const tomorrow = new Date(today.getTime());
    tomorrow.setDate(tomorrow.getDate() + 1);
    const year = candidate.getTime() > tomorrow.getTime() ? thisYear - 1 : thisYear;
    return (year % 100) * 10000 + mm * 100 + dd;
  }
  return 0;
}

function episodeDateScore(episode: DesktopEpisodeItem, today: Date): number {
  if (typeof episode.mtime === "number" && Number.isFinite(episode.mtime)) return episode.mtime;
  return parseUpdateScore(episode.date ?? null, today);
}

/** `List<EpisodeItem>.sortedWithOrder(order)`. */
export function sortEpisodes(
  list: readonly DesktopEpisodeItem[],
  order: EpisodeSortOrder,
  today: Date = new Date(),
): DesktopEpisodeItem[] {
  const copy = [...list];
  switch (order) {
    case "DEFAULT":
      return copy;
    case "NAME_ASC":
      return copy.sort((a, b) => naturalCompare(a.title, b.title));
    case "NAME_DESC":
      return copy.sort((a, b) => naturalCompare(b.title, a.title));
    case "DATE_DESC":
      return copy.sort((a, b) => {
        const diff = episodeDateScore(b, today) - episodeDateScore(a, today);
        return diff !== 0 ? diff : naturalCompare(b.title, a.title);
      });
    case "DATE_ASC":
      return copy.sort((a, b) => {
        const scoreA = episodeDateScore(a, today);
        const scoreB = episodeDateScore(b, today);
        // Kotlin uses `Long.MAX_VALUE` when a date is unknown, so undated chapters sort last.
        const keyA = scoreA > 0 ? scoreA : Number.MAX_SAFE_INTEGER;
        const keyB = scoreB > 0 ? scoreB : Number.MAX_SAFE_INTEGER;
        const diff = keyA - keyB;
        return diff !== 0 ? diff : naturalCompare(a.title, b.title);
      });
  }
}

// ---------------------------------------------------------------------------------------------
// browse tabs (BrowseTab.forSource / afterSourceChange)
// ---------------------------------------------------------------------------------------------

export const FAVORITE_CATALOG_ID = "FAVORITE";

/** Stable `BrowseTab` identity; also the catalog cache key. */
export function browseTabId(sourceId: string, catalogId: string): string {
  return `${sourceId}:${catalogId}`;
}

/** `BrowseTab.forSource`: the favorite tab sits right after `LATEST` when the source has one. */
export function browseTabsForSource(source: {
  id: string;
  catalogs: readonly { id: string; label: string; paginated: boolean }[];
}): DesktopBrowseTab[] {
  const remotes: DesktopBrowseTab[] = (source.catalogs ?? []).map((catalog) => ({
    kind: "catalog",
    id: browseTabId(source.id, catalog.id),
    sourceId: source.id,
    catalogId: catalog.id,
    label: catalog.label,
    paginated: catalog.paginated,
  }));
  const favorite: DesktopBrowseTab = {
    kind: "favorite",
    id: browseTabId(source.id, FAVORITE_CATALOG_ID),
    sourceId: source.id,
    catalogId: FAVORITE_CATALOG_ID,
    label: "즐겨찾기",
    paginated: false,
  };
  const latestAt = remotes.findIndex(
    (tab) => tab.kind === "catalog" && tab.catalogId.toLowerCase() === "latest",
  );
  return latestAt >= 0
    ? [...remotes.slice(0, latestAt + 1), favorite, ...remotes.slice(latestAt + 1)]
    : [...remotes, favorite];
}

/** `BrowseTab.afterSourceChange`: keep the user's catalog when the new source offers it. */
export function afterSourceChange(
  current: DesktopBrowseTab | null,
  nextTabs: readonly DesktopBrowseTab[],
): DesktopBrowseTab | null {
  if (nextTabs.length === 0) return null;
  if (!current) return nextTabs[0] ?? null;
  if (current.kind === "favorite") {
    return nextTabs.find((tab) => tab.kind === "favorite") ?? nextTabs[0] ?? null;
  }
  return (
    nextTabs.find(
      (tab) => tab.kind === "catalog" && tab.catalogId === current.catalogId,
    ) ??
    nextTabs[0] ??
    null
  );
}

// ---------------------------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------------------------

export interface ReleaseHistoryItem {
  versionName: string;
  versionCode: number;
  date: string;
  platform: string;
  releaseNotes: string;
}

export interface DesktopUpdateState {
  isChecking: boolean;
  hasUpdate: boolean;
  currentVersion: string;
  latestVersion: string;
  downloadUrl: string;
  releaseNotes: string;
  error: string | null;
  history: ReleaseHistoryItem[];
}

/**
 * Version comparison matching `core/src/main/kotlin/sync/AppUpdateChecker.kt#isNewerVersion`.
 */
export function isNewerVersion(
  remoteVersion: string,
  remoteCode: number,
  currentVersion: string,
  currentCode: number,
): boolean {
  if (remoteCode > 0 && currentCode > 0) {
    return remoteCode > currentCode;
  }
  const clean = (v: string) =>
    v
      .replace(/^v/, "")
      .split(".")
      .map((n) => parseInt(n, 10) || 0);
  const remoteParts = clean(remoteVersion);
  const currentParts = clean(currentVersion);
  const maxLen = Math.max(remoteParts.length, currentParts.length);
  for (let i = 0; i < maxLen; i++) {
    const r = remoteParts[i] ?? 0;
    const c = currentParts[i] ?? 0;
    if (r > c) return true;
    if (r < c) return false;
  }
  return false;
}

/** Precedence of the numbers {@link DesktopStore.totalEpisodesOf} may return. */
const EPISODE_TOTAL_RANK = { estimate: 0, counted: 1, reported: 2 } as const;
type EpisodeTotalRank = (typeof EPISODE_TOTAL_RANK)[keyof typeof EPISODE_TOTAL_RANK];

/** Fallback page size of the estimate, for a page the plugin answered with no items at all. */
const ESTIMATE_PAGE_SIZE = 20;

/**
 * First positive total among the candidates, or `0` when none is known.
 *
 * Callers pass the candidates in authority order — authoritative total, counted sweep, loaded page
 * length — so the header of `views/SeriesPane.svelte` and the subtitle of `ui/TopBar.svelte` can
 * never disagree about the same work.
 */
export function resolveEpisodeTotal(...candidates: (number | null | undefined)[]): number {
  for (const candidate of candidates) {
    if (typeof candidate !== "number" || !Number.isFinite(candidate)) continue;
    const value = Math.trunc(candidate);
    if (value > 0) return value;
  }
  return 0;
}

/** `WorkId.storageKey()` — the key for `readCounts` / `catalogTotals`. */
export function workKey(sourceId: string, toonId: string): string {
  return `${sourceId}:${toonId}`;
}

/**
 * `com.comics8.core.source.SourceType`, narrowed to what the web shell can actually meet.
 *
 * `SourceSummary` carries no type, so the classification is the one `SourceTitleDropdown.svelte`
 * already uses: `local` is the local library, `network-…` is a network library and everything else
 * is a JS plugin (`WorkId.LOCAL_SOURCE` / `NetworkLibrarySource.idPrefix`).
 */
export type SourceKind = "LOCAL" | "WEBDAV" | "JS";

/** `ComicSource.resolveSourceType()`. */
export function resolveSourceKind(sourceId: string): SourceKind {
  if (sourceId === LOCAL_SOURCE) return "LOCAL";
  if (sourceId.startsWith("network-")) {
    return "WEBDAV";
  }
  return "JS";
}

/** `SourceType.isStorage` — the two storage sections of `SourceManagerPane` (`local`/WebDAV). */
export function isStorageSource(sourceId: string): boolean {
  const kind = resolveSourceKind(sourceId);
  return kind === "LOCAL" || kind === "WEBDAV";
}

/** `ComicSource.displayTitle(strings)` for the local library (`strings.labelSourceLocal`). */
export function sourceDisplayTitle(source: { id: string; displayName: string }): string {
  return source.id === LOCAL_SOURCE && source.displayName.trim() === ""
    ? "로컬 저장소"
    : source.displayName;
}

/** `SourceType.displayLabel(strings)` — the subtitle of an online / JS source row. */
export function sourceKindLabel(kind: SourceKind): string {
  switch (kind) {
    case "LOCAL":
      return "로컬 저장소";
    case "WEBDAV":
      return "WebDAV 클라우드";
    default:
      return "JS 확장 스크립트";
  }
}

// ---------------------------------------------------------------------------------------------
// settings labels (`com.comics8.core.i18n.I18nExtensions.kt` + `AppLanguage.kt`)
// ---------------------------------------------------------------------------------------------

/** `SettingsPane`'s `titleText` — the hub title (`strings.navSettings`) or the category title. */
export function settingsTitleFor(category: SettingsCategory | null): string {
  switch (category) {
    case "GENERAL":
      return "일반";
    case "READER":
      return "뷰어 기본 설정";
    case "SYNC_BACKUP":
      return "동기화 및 백업";
    case "NETWORK_DOWNLOAD":
      return "네트워크 설정";
    case "ABOUT":
      return "앱 정보 및 기타";
    default:
      return "설정";
  }
}

/** `AppLanguage.nativeName`, with `strings.langAuto` standing in for `AUTO`. */
export function appLanguageLabel(language: AppLanguage): string {
  switch (language) {
    case "AUTO":
      return "자동 (Auto)";
    case "KO":
      return "한국어";
    case "EN":
      return "English";
    case "JA":
      return "日本語";
    case "ZH_CN":
      return "简体中文";
    case "ZH_TW":
      return "繁體中文";
  }
}

/**
 * `AppLanguage.resolve(selected, systemLocaleTag)` — `AUTO` follows the platform locale.
 *
 * The Kotlin fallback for an unknown tag is `KO`, which is kept here so both clients agree.
 */
export function resolveAppLanguage(
  selected: AppLanguage,
  systemLocaleTag: string = defaultLocaleTag(),
): AppLanguage {
  if (selected !== "AUTO") return selected;
  const tag = systemLocaleTag.toLowerCase();
  if (tag.startsWith("ko")) return "KO";
  if (tag.startsWith("ja")) return "JA";
  if (tag.startsWith("zh")) {
    return tag.includes("tw") || tag.includes("hk") || tag.includes("mo") || tag.includes("hant")
      ? "ZH_TW"
      : "ZH_CN";
  }
  if (tag.startsWith("en")) return "EN";
  return "KO";
}

function defaultLocaleTag(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().locale ?? "ko";
  } catch {
    return "ko";
  }
}

/** `ViewMode.displayLabel(strings)`. */
export function viewModeLabel(mode: ViewMode): string {
  switch (mode) {
    case "SCROLL":
      return "세로 스크롤";
    case "PAGE":
      return "단면 보기";
    default:
      return "양면 분할";
  }
}

/** `ViewMode.displayShortLabel(strings)` — the top bar chip / hub summary. */
export function viewModeShortLabel(mode: ViewMode): string {
  switch (mode) {
    case "SCROLL":
      return "스크롤";
    case "PAGE":
      return "단면";
    default:
      return "양면";
  }
}

/** `ViewMode.displayDescription(strings)`. */
export function viewModeDescription(mode: ViewMode): string {
  switch (mode) {
    case "SCROLL":
      return "세로로 연속 스크롤하며 읽기";
    case "PAGE":
      return "화면에 한 장씩 넘겨보기";
    default:
      return "화면에 두 장을 좌우로 펼쳐보기";
  }
}

/** `ReadDirection.displayLabel(strings)`. */
export function readDirectionLabel(direction: ReadDirection): string {
  return direction === "RIGHT_TO_LEFT"
    ? "오른쪽 → 왼쪽 (만화)"
    : "왼쪽 → 오른쪽 (일반)";
}

/** `ReadDirection.displayShortLabel(strings)`. */
export function readDirectionShortLabel(direction: ReadDirection): string {
  return direction === "RIGHT_TO_LEFT" ? "우→좌" : "좌→우";
}

/** `ReadDirection.displayDescription(strings)`. */
export function readDirectionDescription(direction: ReadDirection): string {
  return direction === "RIGHT_TO_LEFT"
    ? "우측에서 좌측으로 넘김 (일본 만화 등)"
    : "좌측에서 우측으로 넘김 (웹툰/일반 서적)";
}

/** `ReaderSettingsPane`'s split-mode copy (the Kotlin pane hard-codes these two pairs). */
export function splitModeLabel(mode: SplitMode): string {
  return mode === "FIT" ? "화면 맞춤 (Fit)" : "2장 분할 (Slice)";
}

export function splitModeDescription(mode: SplitMode): string {
  return mode === "FIT"
    ? "와이드 2페이지 펼침 이미지를 화면 비율에 맞춰 한 번에 표시합니다."
    : "와이드 2페이지 펼침 이미지를 좌/우 페이지로 분할하여 순서대로 표시합니다.";
}

/** Desktop's default source for local/library entries. */
const LOCAL_SOURCE = "local";
/** Debounce before a page turn is written to the history store (`pageSaveJob` delay). */
const PAGE_SAVE_DEBOUNCE_MS = 400;
/** `JsPackStore.MAX_SCRIPT_BYTES` — an oversized plugin file is rejected before it is read. */
const MAX_SOURCE_SCRIPT_BYTES = 2 * 1024 * 1024;
/** `chrome.storage.local` key holding desktop-side preferences (active source, sort, viewer). */
export const DESKTOP_PREFS_KEY = "comics8.desktop.prefs";

type DesktopPrefs = {
  activeSourceId: string | null;
  /** `ApplicationPrefs`'s stored `app_language` code (`AppLanguage.code`, `""` = AUTO). */
  appLanguage: AppLanguage;
  episodeSortOrders: Record<string, EpisodeSortOrder>;
  readerSettings: Record<string, DesktopReaderSetting>;
  /** `NetworkSettingsStorage`-equivalent proxy configuration. */
  networkSettings: NetworkSettings;
  /** `SourcePrefs.setInstalledIds` order: `local` first, then storage, then online sources. */
  sourceOrder: string;
  /** `SourcePrefs.setEnabled` — an id missing here is a source the user switched off. */
  enabledSourceIds: string;
};

function emptyPrefs(): DesktopPrefs {
  return {
    activeSourceId: null,
    appLanguage: "AUTO",
    episodeSortOrders: {},
    readerSettings: {},
    networkSettings: { proxyType: "DIRECT", customProxy: { ...EMPTY_NETWORK_SETTINGS.customProxy } },
    sourceOrder: "",
    enabledSourceIds: "",
  };
}

/** `SourcePrefs.parseIdList`: a comma separated preference string with blanks dropped. */
function parseIdList(value: unknown): string[] {
  if (typeof value !== "string") return [];
  return value
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry) => entry !== "");
}

/** `SourcePrefs.formatIdList`: the same list back as the stored preference string. */
function formatIdList(ids: readonly string[]): string {
  return ids.filter((id) => id.trim() !== "").join(",");
}

function isOneOf<T extends string>(value: unknown, allowed: readonly T[]): value is T {
  return typeof value === "string" && (allowed as readonly string[]).includes(value);
}

function parsePrefs(value: unknown): DesktopPrefs {
  const prefs = emptyPrefs();
  if (typeof value !== "object" || value === null) return prefs;
  const record = value as Partial<DesktopPrefs>;
  if (typeof record.activeSourceId === "string" && record.activeSourceId.trim() !== "") {
    prefs.activeSourceId = record.activeSourceId;
  }
  const orders = record.episodeSortOrders;
  if (typeof orders === "object" && orders !== null) {
    for (const [sourceId, order] of Object.entries(orders)) {
      if (isOneOf(order, EPISODE_SORT_ORDERS)) prefs.episodeSortOrders[sourceId] = order;
    }
  }
  if (isOneOf(record.appLanguage, APP_LANGUAGES)) prefs.appLanguage = record.appLanguage;
  const network = record.networkSettings;
  if (typeof network === "object" && network !== null) {
    const candidate = network as Partial<NetworkSettings>;
    const proxy = candidate.customProxy;
    prefs.networkSettings = {
      proxyType: isOneOf(candidate.proxyType, PROXY_TYPES) ? candidate.proxyType : "DIRECT",
      customProxy: {
        host: typeof proxy?.host === "string" ? proxy.host : "",
        port:
          typeof proxy?.port === "number" && proxy.port >= 1 && proxy.port <= 65535
            ? Math.trunc(proxy.port)
            : 1080,
        username: typeof proxy?.username === "string" ? proxy.username : "",
        password: typeof proxy?.password === "string" ? proxy.password : "",
      },
    };
  }
  const settings = record.readerSettings;
  if (typeof settings === "object" && settings !== null) {
    for (const [key, setting] of Object.entries(settings)) {
      if (typeof setting !== "object" || setting === null) continue;
      const candidate = setting as Partial<DesktopReaderSetting>;
      if (
        !isOneOf(candidate.viewMode, VIEW_MODES) ||
        !isOneOf(candidate.readDirection, READ_DIRECTIONS) ||
        !isOneOf(candidate.splitMode, SPLIT_MODES)
      ) {
        continue;
      }
      prefs.readerSettings[key] = {
        viewMode: candidate.viewMode,
        readDirection: candidate.readDirection,
        splitMode: candidate.splitMode,
      };
    }
  }
  prefs.sourceOrder = parseIdList(record.sourceOrder).join(",");
  prefs.enabledSourceIds = parseIdList(record.enabledSourceIds).join(",");
  return prefs;
}

function chromeStorageArea(): chrome.storage.StorageArea | null {
  if (typeof chrome === "undefined" || !chrome.storage?.local) return null;
  return chrome.storage.local;
}

/**
 * Pagination keys a plugin may report, at the result level or inside `pageInfo`.
 *
 * The plugin guide only documents `{ currentPage, lastPage }`, but sources in the wild (and older
 * scripts) use `page` / `maxPage` / `total_pages`, sometimes on the listing object instead of on a
 * nested `pageInfo`. {@link normalisePageInfo} accepts every spelling so a catalog never silently
 * loses its page bar, exactly like the desktop host reads `pageInfo.lastPage` with a fallback.
 */
export type PageInfoLike = {
  currentPage?: unknown;
  page?: unknown;
  lastPage?: unknown;
  maxPage?: unknown;
  total_pages?: unknown;
  totalPages?: unknown;
  pageInfo?: PageInfoLike | null;
};

/** A 1-indexed page pair plus whether the plugin actually reported a total. */
export type NormalisedPageInfo = PageInfo & {
  /**
   * `false` when no total was reported (`lastPage` then equals `currentPage`). The browse page bar
   * keeps working in that case: it labels the page `"N / ?"` instead of inventing a denominator.
   */
  lastPageKnown: boolean;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/** Positive integer from a number or a numeric string; `null` for anything else. */
function toPageNumber(value: unknown): number | null {
  const parsed =
    typeof value === "number"
      ? value
      : typeof value === "string" && value.trim() !== ""
        ? Number.parseInt(value.trim(), 10)
        : Number.NaN;
  if (!Number.isFinite(parsed)) return null;
  const page = Math.trunc(parsed);
  return page > 0 ? page : null;
}

/**
 * `normalisePageInfo`: 1-indexed pages, `lastPage` never below `currentPage`.
 *
 * Accepts the whole listing result as well as a bare page-info object, and reads every convention a
 * plugin may use, in the documented order:
 * `currentPage ?? page ?? result.currentPage ?? result.page ?? fallback` and
 * `lastPage ?? maxPage ?? result.lastPage ?? result.maxPage ?? result.total_pages`.
 */
export function normalisePageInfo(
  source: PageInfoLike | unknown,
  fallback: number,
): NormalisedPageInfo {
  const outer = asRecord(source) ?? {};
  // A nested `pageInfo` wins, but a partial one still falls through to the result-level keys.
  const info = asRecord(outer.pageInfo) ?? outer;

  const currentPage =
    toPageNumber(info.currentPage) ??
    toPageNumber(info.page) ??
    toPageNumber(outer.currentPage) ??
    toPageNumber(outer.page) ??
    Math.max(1, Math.trunc(Number(fallback) || 1));

  const lastPage =
    toPageNumber(info.lastPage) ??
    toPageNumber(info.maxPage) ??
    toPageNumber(info.total_pages) ??
    toPageNumber(info.totalPages) ??
    toPageNumber(outer.lastPage) ??
    toPageNumber(outer.maxPage) ??
    toPageNumber(outer.total_pages) ??
    toPageNumber(outer.totalPages) ??
    null;

  return {
    currentPage,
    lastPage: Math.max(currentPage, lastPage ?? currentPage),
    lastPageKnown: lastPage !== null,
  };
}

/**
 * Episode total a plugin reported on the page itself, if any.
 *
 * The documented `PageInfo` only carries `currentPage`/`lastPage`, so a plugin that knows the real
 * episode count states it as an extra field. Every spelling a source may use is read, at the result
 * level first and then inside `pageInfo`, mirroring the documented-to-loose order of
 * {@link normalisePageInfo}.
 */
export function reportedEpisodeTotal(result: unknown): number | null {
  const outer = asRecord(result) ?? {};
  const info = asRecord(outer.pageInfo) ?? {};
  for (const container of [outer, info]) {
    for (const field of ["total", "totalItems", "itemCount", "totalEpisodes"] as const) {
      const total = toCountNumber(container[field]);
      if (total !== null) return total;
    }
  }
  return null;
}

/** Strictly positive integer from a number or a numeric string; `null` for anything else. */
function toCountNumber(value: unknown): number | null {
  const parsed =
    typeof value === "number"
      ? value
      : typeof value === "string" && value.trim() !== ""
        ? Number.parseInt(value.trim(), 10)
        : Number.NaN;
  if (!Number.isFinite(parsed)) return null;
  const total = Math.trunc(parsed);
  return total > 0 ? total : null;
}

/** Drops repeated cards; some sources paginate sloppily (same rule as `views/display.ts`). */
function dedupeSeries(list: readonly ToonItem[]): ToonItem[] {
  const seen = new Set<string>();
  const out: ToonItem[] = [];
  for (const item of list) {
    const key = `${item.id}\u0000${item.entryEpisodeId ?? ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

/** Drops repeated chapters by `wrId` (falling back to `href`). */
function dedupeEpisodeList(list: readonly DesktopEpisodeItem[]): DesktopEpisodeItem[] {
  const seen = new Set<string>();
  const out: DesktopEpisodeItem[] = [];
  for (const episode of list) {
    const key = episode.wrId || episode.href;
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(episode);
  }
  return out;
}

function describeError(cause: unknown, fallback: string): string {
  const message =
    cause instanceof Error
      ? cause.message
      : typeof cause === "string"
        ? cause
        : "";
  const trimmed = message.trim();
  return trimmed === "" ? fallback : trimmed;
}

/** Accepts both `ToonItem[]` (`LoadedSource`) and `ListingResult` (sandbox bridge). */
function toonListFromSearchResult(result: ToonItem[] | ListingResult | undefined): ToonItem[] {
  if (Array.isArray(result)) return result;
  if (result && Array.isArray(result.items)) return result.items;
  return [];
}

/** `ReadHistoryRecord.toToonItem()` — the web history row has no `href`, so it stays blank. */
export function toonItemFromHistory(record: HistoryRecord): DesktopToonItem {
  return {
    id: record.toonId,
    title: record.title,
    thumbUrl: record.thumbUrl,
    href: "",
    genre: "",
    updatedAt: null,
    sourceId: record.sourceId,
  };
}

function favoriteToToon(record: FavoriteRecord): DesktopToonItem {
  return {
    id: record.id,
    title: record.title,
    thumbUrl: record.thumbUrl,
    href: record.href,
    genre: record.genre,
    updatedAt: record.updatedAt,
    sourceId: record.sourceId,
    isFavorite: true,
  };
}

// ---------------------------------------------------------------------------------------------
// the store
// ---------------------------------------------------------------------------------------------

export type DesktopStoreOptions = {
  /** Shared registry; a private one is created when omitted (mirrors the view props). */
  registry?: SourceRegistry;
  /** Disables `chrome.storage.local` preference persistence (tests). */
  persistPreferences?: boolean;
  /** Sync client backing {@link DesktopStore.syncState}; a private one is created when omitted. */
  syncClient?: SyncClient;
  /**
   * Download manager backing the `download*` fields; a private one is created when omitted.
   *
   * Tests pass a memory-only instance so no request ever leaves the component.
   */
  downloadStore?: DownloadStore;
};

/**
 * Reactive desktop read model.
 *
 * Every `$state` field below maps to a `DesktopUiState` property of the same (camelCased) name;
 * every public method maps to the `DesktopViewModel` function of the same name. Fields that the
 * spec does not list (`loading`, `error`, `refreshing`, …) exist because the desktop actions below
 * cannot be implemented without them.
 */
export class DesktopStore {
  // --- DesktopUiState -------------------------------------------------------------------------

  screen = $state<Screen>("Browse");
  activeSourceId = $state<string | null>(null);
  installedSources = $state<SourceSummary[]>([]);

  series = $state<DesktopToonItem | null>(null);
  seriesHistory = $state<HistoryRecord | null>(null);
  seriesFavorited = $state(false);

  rawEpisodes = $state<DesktopEpisodeItem[]>([]);
  episodes = $state<DesktopEpisodeItem[]>([]);
  episodeSortOrder = $state<EpisodeSortOrder>("DEFAULT");
  episodePage = $state(1);
  episodeLastPage = $state(1);
  episodeLoading = $state(false);
  episodeError = $state<string | null>(null);
  highlightedEpisodeId = $state<string | null>(null);

  currentEpisode = $state<DesktopEpisodeItem | null>(null);
  readerImages = $state<string[]>([]);
  /** `DesktopUiState.imageAspectRatios`, keyed by image index (`width / height`). */
  imageAspectRatios = $state<Record<number, number>>({});
  readerLoading = $state(false);
  readerError = $state<string | null>(null);
  readerToastMessage = $state<string | null>(null);

  viewMode = $state<ViewMode>("DUAL");
  readDirection = $state<ReadDirection>("RIGHT_TO_LEFT");
  splitMode = $state<SplitMode>("FIT");
  /** `DesktopUiState.isFullscreen`, toggled by the reader's `F` shortcut. */
  isFullscreen = $state(false);

  /** `DesktopUiState.showPageJump`, driven by {@link togglePageJump}. */
  showPageJump = $state(false);

  /** `DesktopUiState.showSyncDialog`, driven by {@link openSyncDialog}/{@link closeSyncDialog}. */
  showSyncDialog = $state(false);

  // --- downloads (`DesktopUiState.downloadSummaries` … `downloadProgress`) -----------------------

  /** `DesktopUiState.downloadSummaries` (`DownloadedToonSummary` per work). */
  downloadSummaries = $state<DownloadedToonSummary[]>([]);

  /** `DesktopUiState.downloadLoading`. */
  downloadLoading = $state(false);

  /** `DesktopUiState.showDownloadOptions` — the `DesktopDownloadDialog` visibility. */
  showDownloadOptions = $state(false);

  /** `DesktopUiState.downloadCatalog` — the full episode list the dialog offers. */
  downloadCatalog = $state<DesktopEpisodeItem[]>([]);

  /** `DesktopUiState.downloadCatalogLoading`. */
  downloadCatalogLoading = $state(false);

  /** `DesktopUiState.downloadedWrIds` — chapters of the open series already on disk. */
  downloadedWrIds = $state<Set<string>>(new Set());

  /** `DesktopUiState.downloadProgress` (`DownloadProgressState`). */
  downloadProgress = $state<DownloadProgressState>({ ...EMPTY_DOWNLOAD_PROGRESS });

  // --- settings (`DesktopUiState` fields of `ui/settings/*` plus the web-only sync/WebDAV state) ---

  /**
   * `DesktopUiState.selectedSettingsCategory` — `null` is the category hub.
   *
   * The Kotlin field lives inside `…ui.settings`'s `SettingsCategory` enum; the web twin is the
   * {@link SettingsCategory} union so the pane can compare it directly.
   */
  selectedSettingsCategory = $state<SettingsCategory | null>(null);

  /** `DesktopUiState.appLanguage` (persisted as `AppLanguage.code`). */
  appLanguage = $state<AppLanguage>("AUTO");

  /** `DesktopUiState.networkSettings` — proxy configuration of the network settings pane. */
  networkSettings = $state<NetworkSettings>({
    proxyType: "DIRECT",
    customProxy: { ...EMPTY_NETWORK_SETTINGS.customProxy },
  });

  /** `DesktopUiState.proxyTesting`. */
  proxyTesting = $state(false);

  /** `DesktopUiState.proxyTestResult`. */
  proxyTestResult = $state<string | null>(null);

  /** `DesktopUiState.proxyTestSuccess`; `null` until a test has run. */
  proxyTestSuccess = $state<boolean | null>(null);

  /**
   * `DesktopUiState.syncState` (`com.comics8.core.model.SyncState`).
   *
   * The web shell keeps the credentials in `SyncClient` (`comics8.sync.state`), so the fields below
   * mirror the Kotlin shape rather than owning storage: {@link refreshSyncState} copies them from the
   * client, {@link syncNow} / {@link generateNewSyncKey} / {@link toggleAutoSync} write back.
   */
  syncState = $state({
    isSyncing: false,
    isSuccess: null as boolean | null,
    lastSyncedAt: 0,
    syncKey: "",
    serverUrl: "",
    autoSyncEnabled: true,
    isPro: false,
    syncMessage: null as string | null,
  });

  /** `DesktopUiState.updateState` (`com.comics8.core.model.AppUpdateState`). */
  updateState = $state<DesktopUpdateState>({
    isChecking: false,
    hasUpdate: false,
    currentVersion: "0.2.48",
    latestVersion: "0.2.48",
    downloadUrl: "https://github.com/crudust/comics8/releases",
    releaseNotes: "",
    error: null,
    history: [],
  });

  searchInput = $state("");
  searchQuery = $state<string | null>(null);
  searchExpanded = $state(false);
  searchSuggestions = $state<SearchSuggestion[]>([]);
  /** `DesktopUiState.sourceError` — the banner of the source manager (`setSourceEnabled`/import). */
  sourceError = $state<string | null>(null);

  items = $state<DesktopToonItem[]>([]);
  page = $state(1);
  lastPage = $state(1);
  /** `false` while the plugin reported no total; the page bar then shows `"N / ?"`. */
  lastPageKnown = $state(false);
  loading = $state(false);
  refreshing = $state(false);
  error = $state<string | null>(null);

  browseTabs = $state<DesktopBrowseTab[]>([]);
  activeTab = $state<DesktopBrowseTab | null>(null);

  historyItems = $state<HistoryRecord[]>([]);
  historyLoading = $state(false);
  readCounts = $state<Record<string, number>>({});
  catalogTotals = $state<Record<string, CatalogTotal>>({});
  /**
   * Authoritative episode total per work (`sourceId:toonId`), read by the series header.
   *
   * Highest authority wins: a total the plugin itself reported, then the counted row
   * {@link fetchEpisodeMetadata} wrote into `catalogTotals`. A failed reload keeps the previous
   * number rather than blanking the header.
   */
  totalEpisodesByWork = $state<Record<string, number>>({});

  // --- derived (DesktopUiState computed properties) --------------------------------------------

  /** `DesktopUiState.isSearch`. */
  isSearch = $derived(this.searchQuery !== null);

  /**
   * `DesktopUiState.writesDownloads` — the same rule, in the same order of authority.
   *
   * Kotlin: `series?.sourceId ?: activeSourceId`, then `sourceRegistry.getOrNull(id)?.writesDownloads`,
   * which `ComicSource.kt:22` defines as `kind == SourceKind.REMOTE`. A JS plugin is always a remote
   * source on both clients; the local library (`local`, and any storage source the desktop models as
   * `NetworkLibrarySource`) writes into its own storage and therefore has nothing to download.
   */
  writesDownloads = $derived.by(() => {
    const id = this.series?.sourceId ?? this.activeSourceId;
    if (!id) return false;
    return id !== "local";
  });

  /**
   * `SettingsPane`: the title of the current category, or the hub title (`strings.navSettings`).
   *
   * The Kotlin `titleText` is a `when` over `selectedSettingsCategory`; keeping the strings here
   * means the pane never needs a second lookup table.
   */
  settingsTitle = $derived(settingsTitleFor(this.selectedSettingsCategory));

  /** `episodes.ifEmpty { rawEpisodes }` — the list navigation must walk. */
  #navigationEpisodes = $derived(
    this.episodes.length > 0 ? this.episodes : this.rawEpisodes,
  );

  /** `DesktopUiState.currentEpisodeIndex`. */
  currentEpisodeIndex = $derived.by(() => {
    const episode = this.currentEpisode;
    if (!episode) return -1;
    return this.#navigationEpisodes.findIndex((row) => row.wrId === episode.wrId);
  });

  /** `DesktopUiState.hasNextEpisode`. */
  hasNextEpisode = $derived.by(
    () =>
      nextEpisodeNavigation(
        this.currentEpisodeIndex,
        this.#navigationEpisodes.length,
        this.episodePage,
        this.episodeSortOrder,
      ).kind !== "none",
  );

  /** `DesktopUiState.hasPrevEpisode`. */
  hasPrevEpisode = $derived.by(
    () =>
      prevEpisodeNavigation(
        this.currentEpisodeIndex,
        this.#navigationEpisodes.length,
        this.episodePage,
        this.episodeLastPage,
        this.episodeSortOrder,
      ).kind !== "none",
  );

  // --- private state (DesktopViewModel fields) -------------------------------------------------

  readonly #registry: SourceRegistry;
  readonly #persistPreferences: boolean;
  readonly #sources = new Map<string, AnyLoadedSource>();
  readonly #catalogCache = new Map<string, DesktopToonItem[]>();
  readonly #catalogPages = new Map<
    string,
    { page: number; lastPage: number; lastPageKnown: boolean }
  >();
  /**
   * `ReadHistoryRecord.lastReadOrder` per work.
   *
   * The web history row stops at "where in the chapter", so the chapter position the descriptor
   * badge needs is learned through {@link #learnReadOrder} from `ReaderDomain.episodePosition`.
   */
  #lastReadOrder: Record<string, number> = {};

  /** Authority of the value currently stored in `totalEpisodesByWork[key]`. */
  #episodeTotalRanks: Record<string, EpisodeTotalRank> = {};

  #sortOrders: Record<string, EpisodeSortOrder> = {};
  #readerSettings: Record<string, DesktopReaderSetting> = {};
  #activeSourcePref: string | null = null;
  /**
   * Cloud-sync client behind {@link syncState}.
   *
   * `SyncClient` owns the credentials (`comics8.sync.state`); the store only mirrors its status into
   * the reactive {@link syncState} so the settings pane can render it without awaiting anything.
   */
  readonly #syncClient: SyncClient;
  /**
   * `repository.downloadManager` — the serial download queue + manifest behind
   * {@link downloadProgress} / {@link downloadedWrIds} / {@link downloadSummaries}.
   */
  readonly #downloads: DownloadStore;
  /** `SourcePrefs.INSTALLED_KEY` order: `local`, storage sources, then online/JS sources. */
  #sourceOrder = "";
  /** `SourcePrefs.INSTALLED_KEY` membership: an empty list means "nothing switched off yet". */
  #enabledSourceIds = "";
  #prefsLoaded = false;
  #writeChain: Promise<void> = Promise.resolve();

  #loadToken = 0;
  #episodeToken = 0;
  #readerToken = 0;

  #returnScreen: Screen = "Browse";

  #pageSaveTimer: ReturnType<typeof setTimeout> | null = null;
  #pendingPageSave:
    | { sourceId: string; toonId: string; episode: DesktopEpisodeItem; page: number; total: number }
    | null = null;
  #toastTimer: ReturnType<typeof setTimeout> | null = null;
  #syncDebounceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(options: DesktopStoreOptions = {}) {
    this.#registry = options.registry ?? new SourceRegistry();
    this.#persistPreferences = options.persistPreferences ?? true;
    this.#syncClient = options.syncClient ?? new SyncClient();
    // `repository.downloadManager`: the serial queue + manifest. It hydrates from
    // `chrome.storage.local` / IndexedDB when those exist, and stays in memory otherwise (tests).
    this.#downloads =
      options.downloadStore ??
      new DownloadStore({
        resolveImages: (sourceId, episode, series) =>
          this.#resolveDownloadImages(sourceId, episode, series),
        headersFor: (sourceId) => {
          const meta = this.#sources.get(sourceId)?.meta;
          if (!meta) return undefined;
          const headers: Record<string, string> = { ...meta.extraHeaders };
          if (meta.referer) headers.referer = meta.referer;
          if (meta.userAgent) headers["user-agent"] = meta.userAgent;
          return headers;
        },
        onEpisodeDownloaded: (workKey, wrId) => {
          if (workKey === this.#openWorkKey()) {
            const next = new Set(this.downloadedWrIds);
            next.add(wrId);
            this.downloadedWrIds = next;
          }
          this.downloadSummaries = this.#downloads.getSummaries();
        },
        onProgress: (progress) => {
          this.downloadProgress = { ...progress };
        },
      });
  }

  /** The registry this store loads plugins through (exposed for source-management surfaces). */
  get registry(): SourceRegistry {
    return this.#registry;
  }

  /** Compiled source behind {@link activeSourceId}; `null` before `init()`/`setActiveSource()`. */
  get activeSource(): AnyLoadedSource | null {
    const id = this.activeSourceId;
    return id ? (this.#sources.get(id) ?? null) : null;
  }

  /** Hydrates stores/preferences and re-opens the last active source. Safe to call twice. */
  async init(): Promise<void> {
    await Promise.all([
      catalogStore.init().catch(() => undefined),
      favoritesStore.init().catch(() => undefined),
      historyStore.init().catch(() => undefined),
      this.#registry.init().catch(() => undefined),
    ]);
    // Persisted trace of `DesktopViewModel`'s `catalogTotals`: a count taken in a previous session is
    // reused by `fetchEpisodeMetadata` without sweeping every page again. The rows are counted
    // totals, so they are recorded at counted rank: a page's estimated shape must never replace one.
    this.catalogTotals = mapCatalogTotals(catalogStore.getAll());
    for (const key of Object.keys(this.catalogTotals)) {
      this.#episodeTotalRanks[key] = EPISODE_TOTAL_RANK.counted;
    }
    for (const h of historyStore.getHistory()) {
      if (h.lastReadOrder) {
        const k = workKey(h.sourceId, h.toonId);
        this.#lastReadOrder[k] = Math.max(this.#lastReadOrder[k] ?? 0, h.lastReadOrder);
      }
    }
    await this.#loadPreferences();
    this.installedSources = this.#listSources();

    const preferred =
      this.#activeSourcePref ??
      this.installedSources[0]?.id ??
      null;
    if (preferred) {
      // `setActiveSource` re-reads the registry list, applies the tabs and loads page 1.
      await this.setActiveSource(preferred);
    } else {
      this.#applySource(null);
    }
    await this.loadHistory();
    // `DesktopDownloadManager` is a repository-level singleton: its manifest exists before any
    // screen asks for it, so the downloads pane never has to wait for a first click.
    void this.loadDownloads();
    // Parity with DesktopViewModel.kt line 315: background syncPull on startup
    void this.#triggerStartupSync();
  }

  // --- source ---------------------------------------------------------------------------------

  /** `DesktopViewModel.setActiveSource`: installs the source, its tabs and the first listing. */
  async setActiveSource(sourceId: string): Promise<void> {
    const loaded = await this.#requireSource(sourceId);
    if (!loaded) return;
    this.#activeSourcePref = sourceId;
    void this.#persistPreferencesNow();
    this.#applySource(loaded);
    const tab = this.activeTab;
    if (tab) await this.#loadPage(this.#catalogPages.get(tab.id)?.page ?? 1);
  }

  /** Re-reads the source registry and updates {@link installedSources}. */
  refreshInstalledSources(): void {
    this.installedSources = this.#listSources();
  }

  /**
   * `DesktopViewModel.openSourceManager`: routes to the source manager screen.
   *
   * The Kotlin action cancels the reader/episode jobs first so a slow page never lands on a screen
   * that no longer shows it; bumping the tokens below does the same for the pending loads.
   */
  openSourceManager(): void {
    this.#loadToken += 1;
    this.#episodeToken += 1;
    this.#readerToken += 1;
    this.screen = "SourceManager";
  }

  /** `DesktopViewModel.closeSourceManager`: the manager is a leaf screen, so back lands on Browse. */
  closeSourceManager(): void {
    this.screen = "Browse";
  }

  /**
   * `SourcePrefs.isEnabled` — `true` until the user switches a source off.
   *
   * The desktop list is stored as installed ids, so an empty list means "every registry entry"; the
   * local library can never be switched off (`SourcePrefs.setEnabled` refuses it).
   */
  isSourceEnabled(sourceId: string): boolean {
    if (sourceId === LOCAL_SOURCE) return true;
    const enabled = parseIdList(this.#enabledSourceIds);
    return enabled.length === 0 || enabled.includes(sourceId);
  }

  /**
   * `SourcePrefs.setEnabled`: flips one source on or off and re-reads the source list.
   *
   * Disabling the active source falls back like `SourcePrefs.setEnabled` does (the stored active id
   * is rewritten when the source it points at is switched off). Unlike the desktop action, which
   * routes a switch-off through `uninstallSource`, this only hides the source: the registry entry and
   * its script stay on disk, so switching it back on restores it untouched. Removal is
   * {@link uninstallSource}, the manager's trash button.
   */
  async toggleSourceEnabled(sourceId: string): Promise<void> {
    const id = sourceId.trim();
    if (id === "" || id === LOCAL_SOURCE) return;
    const enabled = parseIdList(this.#enabledSourceIds);
    const isEnabled = enabled.length === 0 || enabled.includes(id);
    // Freeze the implicit "everything on" default into a real list before the first switch-off.
    const base =
      enabled.length === 0 ? this.#registry.list().map((source) => source.id) : enabled;
    this.#enabledSourceIds = formatIdList(
      isEnabled ? base.filter((candidate) => candidate !== id) : [...base, id],
    );
    await this.#persistPreferencesNow();
    this.installedSources = this.#listSources();

    if (isEnabled && this.activeSourceId === id) {
      await this.#activateFallback();
    }
  }

  /**
   * `DesktopViewModel.reorderStorageSources` / `reorderOnlineSources`, in one action.
   *
   * A source only moves inside its own section (`local` / WebDAV first, then web / JS), which
   * is why the result is rebuilt as `local, storage…, online…` — the same `linkedSetOf` order the
   * Kotlin actions write to `SourcePrefs.setInstalledIds`.
   *
   * @param sourceId id of the row the user moved.
   * @param offset   `-1` moves it one step up, `+1` one step down.
   */
  reorderSources(sourceId: string, offset: number): void {
    const current = this.#listSources();
    const storage = current.filter(
      (source) => isStorageSource(source.id) && source.id !== LOCAL_SOURCE,
    );
    const online = current.filter((source) => !isStorageSource(source.id));
    const section = isStorageSource(sourceId) && sourceId !== LOCAL_SOURCE ? storage : online;
    const from = section.findIndex((source) => source.id === sourceId);
    if (from < 0) return;
    const to = Math.min(Math.max(from + Math.trunc(offset), 0), section.length - 1);
    if (from === to) return;

    const moved = [...section];
    const [movedSource] = moved.splice(from, 1);
    if (!movedSource) return;
    moved.splice(to, 0, movedSource);

    const nextStorage = section === storage ? moved : storage;
    const nextOnline = section === online ? moved : online;
    this.#sourceOrder = formatIdList([
      LOCAL_SOURCE,
      ...nextStorage.map((source) => source.id),
      ...nextOnline.map((source) => source.id),
    ]);
    void this.#persistPreferencesNow();
    this.installedSources = this.#listSources();
  }

  /**
   * `DesktopViewModel.uninstallSource`: removes an installed plugin and keeps the store coherent.
   *
   * Every source the manager lists comes from the user (the extension ships no built-in plugin), so
   * uninstalling is always allowed here; only the pinned local library is out of reach.
   */
  async uninstallSource(sourceId: string): Promise<void> {
    const id = sourceId.trim();
    if (id === "" || id === LOCAL_SOURCE) return;
    const removed = await this.#registry.uninstall(id);
    if (!removed) return;

    this.#sources.delete(id);
    this.#sourceOrder = formatIdList(
      parseIdList(this.#sourceOrder).filter((candidate) => candidate !== id),
    );
    this.#enabledSourceIds = formatIdList(
      parseIdList(this.#enabledSourceIds).filter((candidate) => candidate !== id),
    );
    this.#clearCatalogCache(id);
    void this.#persistPreferencesNow();

    if (this.activeSourceId === id) {
      // `uninstallSource` ends with `applyActiveSource(WorkId.LOCAL_SOURCE, loadListing = true)`.
      await this.#activateFallback();
      return;
    }
    this.installedSources = this.#listSources();
  }

  /**
   * `DesktopViewModel.importJsFile`: installs a `.js` plugin from the file picker.
   *
   * `JsPackStore.MAX_SCRIPT_BYTES` caps the script before it is read, and `importJsScript` makes the
   * fresh source active afterwards so the user immediately sees its catalogs. A failed import never
   * throws into the UI: it lands in {@link sourceError}, the desktop `sourceError` banner text.
   */
  async importJsFile(file: File): Promise<void> {
    this.sourceError = null;
    try {
      if (file.size > MAX_SOURCE_SCRIPT_BYTES) {
        throw new Error("파일이 너무 큽니다");
      }
      const script = await file.text();
      await this.importJsScript(script, file.name);
    } catch (cause) {
      this.sourceError = describeError(cause, "파일을 읽을 수 없습니다");
    }
  }

  /** `DesktopViewModel.importJsScript`: compiles, stores and activates a plugin script. */
  async importJsScript(script: string, fileName = "source.js"): Promise<void> {
    this.sourceError = null;
    try {
      const installed = await this.#registry.install(script, { replace: true });
      this.#sources.delete(installed.id);
      this.#sourceOrder = formatIdList([
        ...parseIdList(this.#sourceOrder).filter((candidate) => candidate !== installed.id),
        installed.id,
      ]);
      void this.#persistPreferencesNow();
      this.#clearCatalogCache(installed.id);
      this.installedSources = this.#listSources();
      // `importJsScript` ends with `setActiveSource(source.id)`.
      await this.setActiveSource(installed.id);
    } catch (cause) {
      this.sourceError = describeError(cause, `"${fileName}" 가져오기에 실패했습니다`);
    }
  }

  /** Opens an external local archive (e.g. dropped ZIP/CBZ) in the reader. */
  openExternalArchive(title: string, images: string[]): void {
    this.screen = "Reader";
    this.currentEpisode = {
      wrId: `archive-${title}`,
      title,
      href: "",
      isRead: true,
      lastReadPage: 0,
    };
    this.readerImages = images;
    this.imageAspectRatios = {};
    this.readerLoading = false;
    this.readerError = null;
    this.readerToastMessage = null;
  }

  // --- tab / browse -----------------------------------------------------------------------------

  /**
   * `DesktopViewModel.selectTab(tab)`.
   *
   * Accepts the tab object (what `browseTabs` holds) or its id, so a template can pass
   * `desktopState.selectTab(tab.id)` without importing a helper.
   */
  async selectTab(
    tab: DesktopBrowseTab | string,
    options: { force?: boolean } = {},
  ): Promise<void> {
    const target =
      typeof tab === "string"
        ? (this.browseTabs.find((candidate) => candidate.id === tab) ?? null)
        : tab;
    if (!target) return;
    const sameTab = this.activeTab?.id === target.id;
    if (
      !options.force &&
      sameTab &&
      this.screen === "Browse" &&
      this.searchQuery === null
    ) {
      return;
    }

    this.#loadToken += 1;
    this.#episodeToken += 1;
    this.#readerToken += 1;

    const cached = this.#catalogCache.get(target.id);
    const saved = this.#catalogPages.get(target.id) ?? {
      page: 1,
      lastPage: 1,
      lastPageKnown: false,
    };
    this.activeTab = target;
    this.page = saved.page;
    this.lastPage = saved.lastPage;
    this.lastPageKnown = saved.lastPageKnown;
    this.showPageJump = false;
    this.items = cached ?? [];
    this.loading = cached === undefined;
    this.error = null;
    this.screen = "Browse";
    this.searchQuery = null;
    this.searchInput = "";
    this.series = null;
    this.seriesHistory = null;
    this.rawEpisodes = [];
    this.episodes = [];
    this.currentEpisode = null;
    this.readerImages = [];
    this.imageAspectRatios = {};
    this.readerError = null;
    this.highlightedEpisodeId = null;

    if (cached === undefined) {
      await this.#loadPage(saved.page);
    } else {
      // Cached page: re-apply favorite flags from the live store instead of a stored snapshot.
      this.items = this.#decorateItems(cached, target.sourceId);
    }
  }

  /** `DesktopViewModel.refresh`: re-checks the current surface, dropping the catalog cache. */
  async refresh(): Promise<void> {
    if (this.activeSourceId === null) return;
    if (this.screen === "Browse") {
      void this.#syncOnBrowseRefresh();
    }
    if (this.screen === "Series" && this.series) {
      await this.#loadEpisodes(this.series, this.episodePage);
      return;
    }
    if (this.searchQuery !== null) {
      const query = this.searchQuery;
      if (query.trim() !== "") await this.search(query);
      return;
    }
    if (this.activeTab) {
      this.#catalogCache.delete(this.activeTab.id);
      await this.#loadPage(this.page, { replace: true });
    }
  }

  /**
   * `DesktopViewModel.syncOnBrowseRefresh`: background sync triggered on browse pull-to-refresh or refresh button.
   */
  async #syncOnBrowseRefresh(): Promise<void> {
    if (this.syncState.isSyncing) return;
    try {
      const res = await this.syncNow();
      if (res.ok && (res.favorites > 0 || res.history > 0 || res.readEpisodes > 0)) {
        if (this.screen === "Browse" && this.searchQuery === null && this.activeTab) {
          await this.#loadPage(this.page, { replace: true });
        }
      }
    } catch {
      // Background refresh sync is opportunistic; quiet catch like Kotlin
    }
  }

  /**
   * `DesktopViewModel.kt line 315`: background sync on app startup.
   */
  async #triggerStartupSync(): Promise<void> {
    if (this.syncState.isSyncing) return;
    try {
      await this.loadSyncState();
      if (!this.syncState.syncKey) return;
      const res = await this.syncNow();
      if (res.ok && (res.favorites > 0 || res.history > 0 || res.readEpisodes > 0)) {
        if (this.screen === "Browse" && this.searchQuery === null && this.activeTab) {
          await this.#loadPage(this.page, { replace: true });
        }
      }
    } catch {
      // Silent on startup failure
    }
  }

  /**
   * `AboutSettingsPane`'s "캐시 삭제": drops every cached catalog page and listing.
   *
   * The desktop action clears the image cache; the web reader keeps no decoded-bitmap store, so the
   * caches it actually holds are the catalog cache ({@link #catalogCache}) and each plugin's sandbox
   * `cachePut` map. Anything that is not cached is fetched again on the next visit, which is exactly
   * what clearing an image cache gives the user.
   */
  clearImageCache(): number {
    const dropped = this.#catalogCache.size + this.#catalogPages.size;
    this.#catalogCache.clear();
    this.#catalogPages.clear();
    this.imageAspectRatios = {};
    return dropped;
  }

  /**
   * `DesktopViewModel.checkForUpdate` / `AppUpdateChecker.checkDesktopUpdate`.
   * Checks for remote version info from the sync server or GitHub and updates {@link updateState}.
   */
  async checkForUpdate(manual = false): Promise<string> {
    if (this.updateState.isChecking) return "이미 확인 중입니다.";
    this.updateState = { ...this.updateState, isChecking: true, error: null };
    try {
      const serverUrl = this.syncState.serverUrl.trim() || DEFAULT_SERVER_URL;
      const apiRoot = serverUrl.replace(/\/sync\/?$/, "").replace(/\/$/, "");
      const versionUrl = `${apiRoot}/version`;

      let json: any = null;
      try {
        const resp = await fetch(versionUrl);
        if (resp.ok) json = await resp.json();
      } catch {
        // Fallback below
      }

      if (!json) {
        try {
          const fbResp = await fetch("https://raw.githubusercontent.com/crudust/comics8/main/server.json");
          if (fbResp.ok) {
            const fbConfig = await fbResp.json();
            if (fbConfig?.serverUrl) {
              const fbRoot = String(fbConfig.serverUrl).replace(/\/sync\/?$/, "").replace(/\/$/, "");
              const vResp = await fetch(`${fbRoot}/version`);
              if (vResp.ok) json = await vResp.json();
            }
          }
        } catch {
          // ignore
        }
      }

      if (!json) {
        const msg = "업데이트 정보를 가져오지 못했습니다.";
        this.updateState = { ...this.updateState, isChecking: false, error: msg };
        return msg;
      }

      const desktopInfo = json.desktop ?? json.android ?? {};
      const remoteVer = String(desktopInfo.versionName ?? "");
      const remoteCode = Number(desktopInfo.versionCode ?? 0);
      const currentVer = this.updateState.currentVersion;
      const hasUpdate = isNewerVersion(remoteVer, remoteCode, currentVer, 0);

      const history: ReleaseHistoryItem[] = Array.isArray(json.history)
        ? json.history.map((h: any) => ({
            versionName: String(h.versionName ?? ""),
            versionCode: Number(h.versionCode ?? 0),
            date: String(h.date ?? ""),
            platform: String(h.platform ?? "all"),
            releaseNotes: String(h.releaseNotes ?? ""),
          }))
        : [];

      const downloadUrl =
        String(desktopInfo.downloadUrl ?? "").trim() || "https://github.com/crudust/comics8/releases";

      this.updateState = {
        isChecking: false,
        hasUpdate,
        currentVersion: currentVer,
        latestVersion: remoteVer || currentVer,
        downloadUrl,
        releaseNotes: String(desktopInfo.releaseNotes ?? ""),
        error: null,
        history,
      };

      if (hasUpdate) {
        return `새 버전(v${remoteVer})이 있습니다!`;
      }
      return `최신 버전(v${currentVer})을 사용 중입니다.`;
    } catch (err: any) {
      const msg = err?.message ?? "업데이트 확인 중 오류가 발생했습니다.";
      this.updateState = { ...this.updateState, isChecking: false, error: msg };
      return msg;
    }
  }

  // --- search -----------------------------------------------------------------------------------

  /** `DesktopViewModel.toggleSearchBar`. */
  toggleSearchBar(): void {
    const next = !this.searchExpanded;
    this.searchExpanded = next;
    if (!next) {
      this.searchInput = "";
      this.searchSuggestions = [];
    }
  }

  /** `DesktopViewModel.search(query)`: keyword results replace the grid (1 page, `searchQuery` set). */
  async search(query: string): Promise<void> {
    const source = this.activeSource;
    const trimmed = query.trim();
    if (!source || trimmed === "") return;

    this.#loadToken += 1;
    const token = this.#loadToken;
    this.searchQuery = trimmed;
    this.searchInput = trimmed;
    this.searchSuggestions = [];
    this.items = [];
    this.page = 1;
    this.lastPage = 1;
    this.lastPageKnown = false;
    this.showPageJump = false;
    this.loading = true;
    this.error = null;
    this.screen = "Browse";

    try {
      const result = await source.search({ text: trimmed });
      if (token !== this.#loadToken) return;
      const cards = dedupeSeries(toonListFromSearchResult(result));
      const totals = await this.#mergeStoredTotals(source.id, cards);
      if (token !== this.#loadToken) return;
      this.items = this.#decorateItems(cards, source.id, { catalogTotals: totals });
      this.loading = false;
      this.error = null;
      // `syncEpisodeCounts` runs after the grid is shown: the sourced listings pop in through the
      // server-side catalog cache instead of blocking the first paint.
      void this.syncEpisodeMetadata(this.items);
    } catch (cause) {
      if (token !== this.#loadToken) return;
      this.loading = false;
      this.error = describeError(cause, "검색에 실패했습니다.");
    }
  }

  /** Leaves search results and restores the selected catalog (`goBack` from a search). */
  async clearSearch(): Promise<void> {
    if (this.searchQuery === null) return;
    this.searchQuery = null;
    this.searchInput = "";
    this.searchSuggestions = [];
    const tab = this.activeTab;
    if (tab) {
      await this.selectTab(tab, { force: true });
    } else {
      this.items = [];
      this.page = 1;
      this.lastPage = 1;
      this.lastPageKnown = false;
    }
  }

  // --- series -----------------------------------------------------------------------------------

  /**
   * `DesktopViewModel.openSeries(item)`: loads the episode list, then the favorite/read-state of the
   * series (both stores are in-memory after `init`, so they are cheap and can land first).
   */
  async openSeries(item: DesktopToonItem): Promise<void> {
    const sourceId = this.#sourceIdFor(item);
    const series: DesktopToonItem = { ...item, sourceId };
    if (this.screen !== "Series" && this.screen !== "Reader") {
      this.#returnScreen = this.screen;
    }

    this.screen = "Series";
    this.series = series;
    this.seriesHistory = null;
    this.rawEpisodes = [];
    this.episodes = [];
    this.episodeSortOrder = this.#sortOrders[sourceId] ?? "DEFAULT";
    this.episodePage = 1;
    this.episodeLastPage = 1;
    this.episodeError = null;
    this.showPageJump = false;
    this.seriesFavorited = false;
    this.highlightedEpisodeId = item.entryEpisodeId ?? null;
    this.currentEpisode = null;

    const episodesPromise = this.#loadEpisodes(series, 1);

    await Promise.all([
      favoritesStore.init().catch(() => undefined),
      historyStore.init().catch(() => undefined),
    ]);
    if (this.series?.id === series.id && this.#sourceIdFor(this.series) === sourceId) {
      this.seriesFavorited = favoritesStore.isFavorite(sourceId, series.id);
      this.seriesHistory = historyStore.getProgress(sourceId, series.id);
      this.#countReadEpisodes(sourceId, series.id);
    }

    // `DesktopViewModel.refreshDownloadedWrIds()`: the cards print `DownloadDone` for the chapters
    // that are already on disk, so the ids are read as soon as the series opens.
    void this.refreshDownloadedWrIds();

    await episodesPromise;
  }

  /** `DesktopViewModel.closeSeries`: back to the grid (or history, when opened from there). */
  async closeSeries(): Promise<void> {
    this.#loadToken += 1;
    this.#episodeToken += 1;
    this.#readerToken += 1;
    const tab = this.activeTab;
    const searching = this.searchQuery !== null;
    const returnScreen = this.#returnScreen;

    this.screen = returnScreen === "History" ? "History" : "Browse";
    this.series = null;
    this.seriesHistory = null;
    this.rawEpisodes = [];
    this.episodes = [];
    this.episodeError = null;
    this.episodeLoading = false;
    this.seriesFavorited = false;
    this.currentEpisode = null;
    this.readerImages = [];
    this.imageAspectRatios = {};
    this.readerError = null;
    this.readerToastMessage = null;
    this.highlightedEpisodeId = null;
    this.showPageJump = false;

    if (this.screen === "History") {
      await this.loadHistory();
      return;
    }
    if (tab && !searching) {
      const cached = this.#catalogCache.get(tab.id);
      if (cached !== undefined) {
        this.items = this.#decorateItems(cached, tab.sourceId);
      } else if (tab.kind === "favorite") {
        await this.#loadPage(1, { replace: true });
      }
    }
  }

  // --- episodes / reader --------------------------------------------------------------------------

  /**
   * `DesktopViewModel.resumeSeries`: reopen the recorded chapter at its saved page, falling back to
   * the oldest chapter when the series has no history yet.
   */
  async resumeSeries(): Promise<void> {
    if (!this.series) return;
    const history = this.seriesHistory;
    if (!history) {
      await this.openFirstEpisode();
      return;
    }
    await this.continueHistoryEpisode(history);
  }

  /**
   * `DesktopViewModel.openFirstEpisode`.
   *
   * The oldest chapter is the *last* item of a source-ordered page (newest first in every bundled
   * plugin), and on a paginated listing it lives on the last page — so the page is loaded rather
   * than guessed, exactly like the Kotlin version does after `fetchEpisodeMetadata`.
   */
  async openFirstEpisode(): Promise<void> {
    const series = this.series;
    if (!series) return;
    const lastPage = Math.max(1, this.episodeLastPage);
    const raw = this.rawEpisodes.length > 0 ? this.rawEpisodes : this.episodes;

    if (lastPage <= 1) {
      const firstEpisode = raw[raw.length - 1];
      if (firstEpisode) {
        await this.openEpisode(firstEpisode);
        return;
      }
    }

    await this.loadEpisodes(lastPage);
    const loaded = this.rawEpisodes.length > 0 ? this.rawEpisodes : this.episodes;
    const firstEpisode = loaded[loaded.length - 1];
    if (firstEpisode) await this.openEpisode(firstEpisode);
  }

  /** `DesktopViewModel.openEpisode(episode)`: opens the reader and starts resolving images. */
  async openEpisode(episode: DesktopEpisodeItem): Promise<void> {
    const series = this.series;
    const sourceId = series ? this.#sourceIdFor(series) : (this.activeSourceId ?? null);

    const startPage = startPageOnOpen(episode.lastReadPage ?? 0);
    const now = Date.now();
    const openEpisode: DesktopEpisodeItem = {
      ...episode,
      isRead: true,
      readAt: now,
      lastReadPage: startPage,
    };

    this.#toastTimer = clearTimeoutOf(this.#toastTimer);
    this.screen = "Reader";
    this.currentEpisode = openEpisode;
    this.readerImages = [];
    this.imageAspectRatios = {};
    this.readerLoading = true;
    this.readerError = null;
    this.readerToastMessage = null;
    this.rawEpisodes = this.rawEpisodes.map((row) =>
      row.wrId === episode.wrId ? { ...row, isRead: true, readAt: now } : row,
    );
    this.episodes = this.episodes.map((row) =>
      row.wrId === episode.wrId ? { ...row, isRead: true, readAt: now } : row,
    );

    if (series && sourceId) {
      const saved = this.#readerSettings[workKey(sourceId, series.id)];
      if (saved) {
        this.viewMode = saved.viewMode;
        this.readDirection = saved.readDirection;
        this.splitMode = saved.splitMode;
      }
      this.#countReadEpisodes(sourceId, series.id);
      this.#learnReadOrder(series, sourceId, openEpisode);
    }

    await this.#loadReaderImages(openEpisode, series);

    if (series && sourceId) {
      await this.#writeProgress(series, sourceId, openEpisode, startPage);
      this.triggerDebouncedPush();
    }
  }

  /** `DesktopViewModel.closeReader`: flush the pending page, return to the episode list. */
  closeReader(): void {
    void this.#flushPageSave();
    this.triggerDebouncedPush();
    this.#readerToken += 1;
    this.#toastTimer = clearTimeoutOf(this.#toastTimer);
    this.screen = "Series";
    this.currentEpisode = null;
    this.readerImages = [];
    this.imageAspectRatios = {};
    this.readerLoading = false;
    this.readerError = null;
    this.readerToastMessage = null;
  }

  /** `DesktopViewModel.openNextEpisode` (respects the sort order and the episode page edges). */
  async nextEpisode(): Promise<void> {
    const navigation = nextEpisodeNavigation(
      this.currentEpisodeIndex,
      this.#navigationEpisodes.length,
      this.episodePage,
      this.episodeSortOrder,
    );
    await this.#applyNavigation(navigation);
  }

  /** `DesktopViewModel.openPrevEpisode`. */
  async prevEpisode(): Promise<void> {
    const navigation = prevEpisodeNavigation(
      this.currentEpisodeIndex,
      this.#navigationEpisodes.length,
      this.episodePage,
      this.episodeLastPage,
      this.episodeSortOrder,
    );
    await this.#applyNavigation(navigation);
  }

  /**
   * `DesktopViewModel.loadEpisodes(item, page)`: reloads an episode page (the retry action of the
   * series pane's error state). Defaults to the page already shown.
   */
  async loadEpisodes(page: number = this.episodePage): Promise<void> {
    const series = this.series;
    if (!series) return;
    await this.#loadEpisodes(series, Math.max(1, Math.trunc(page)));
  }

  /** `DesktopViewModel.goToEpisodePage`, clamped against `episodeLastPage`. */
  async goToEpisodePage(page: number): Promise<void> {
    const series = this.series;
    if (!series) return;
    await this.#loadEpisodes(series, clampPage(page, this.episodeLastPage));
  }

  /**
   * `DesktopViewModel.goToPage(page)`.
   *
   * Only a paginated catalog may turn its page (`if (state.tab?.paginated != true) return`); the
   * cards are blanked before the load so the previous page never sits under a new page label.
   */
  async goToPage(page: number): Promise<void> {
    if (this.activeTab?.paginated !== true) return;
    // An unknown total cannot bound the target: only the lower edge is clamped, so a jump can still
    // reach a page beyond the one currently shown (`lastPage` is then just a placeholder).
    const requested = Math.max(1, Math.trunc(Number(page) || 1));
    const clamped = this.lastPageKnown ? clampPage(requested, this.lastPage) : requested;
    this.items = [];
    this.loading = true;
    this.error = null;
    this.page = clamped;
    this.showPageJump = false;
    await this.#loadPage(clamped, { replace: true });
  }

  /**
   * `DesktopViewModel.togglePageJump(show)`.
   *
   * With no argument it flips the dialog, which is what a trigger button wants; passing `false` is
   * the dialog's dismiss path (`onDismiss = { viewModel.togglePageJump(false) }`).
   */
  togglePageJump(show?: boolean): void {
    this.showPageJump = show ?? !this.showPageJump;
  }

  /**
   * `DesktopViewModel.nextPage`: `if (state.page < state.lastPage) goToPage(state.page + 1)`.
   *
   * When the plugin reports no total there is no upper bound to compare against, so the turn is
   * always attempted; an empty page is then the natural end of the listing.
   */
  async nextPage(): Promise<void> {
    if (!this.lastPageKnown || this.page < this.lastPage) await this.goToPage(this.page + 1);
  }

  /** `DesktopViewModel.prevPage`: `if (state.page > 1) goToPage(state.page - 1)`. */
  async prevPage(): Promise<void> {
    if (this.page > 1) await this.goToPage(this.page - 1);
  }

  /** `DesktopViewModel.setEpisodeSortOrder`: persists per source and re-sorts the loaded page. */
  setEpisodeSortOrder(order: EpisodeSortOrder): void {
    const sourceId = this.series
      ? this.#sourceIdFor(this.series)
      : (this.activeSourceId ?? LOCAL_SOURCE);
    this.#sortOrders[sourceId] = order;
    this.episodeSortOrder = order;
    if (this.series && this.#sourceIdFor(this.series) === sourceId) {
      const base = this.rawEpisodes.length > 0 ? this.rawEpisodes : this.episodes;
      this.episodes = sortEpisodes(base, order);
    }
    void this.#persistPreferencesNow();
  }

  /**
   * `DesktopViewModel.toggleFavorite()` / `toggleFavorite(item)`.
   *
   * With no argument it favorites the currently open series (the series header); with an item it
   * stars that card (the grid, the history list). Both paths keep the visible grid, the cached
   * catalog page and `seriesFavorited` in sync, like the two Kotlin entry points do.
   */
  async toggleFavorite(item?: DesktopToonItem | null): Promise<void> {
    const target = item ?? this.series;
    if (!target) return;
    const sourceId = this.#sourceIdFor(target);
    const favorited = await favoritesStore.toggleFavorite(target, sourceId);
    const targetKey = workKey(sourceId, target.id);

    this.items = this.items.map((row) =>
      workKey(this.#sourceIdFor(row), row.id) === targetKey
        ? { ...row, isFavorite: favorited }
        : row,
    );
    if (
      !favorited &&
      this.activeTab?.kind === "favorite" &&
      this.screen === "Browse" &&
      this.searchQuery === null
    ) {
      this.items = this.items.filter(
        (row) => workKey(this.#sourceIdFor(row), row.id) !== targetKey,
      );
    }
    if (this.series && workKey(this.#sourceIdFor(this.series), this.series.id) === targetKey) {
      this.seriesFavorited = favorited;
    }
    const tab = this.activeTab;
    if (tab && tab.kind === "catalog") {
      // Keep the cached page consistent with what is displayed (`catalogCache` in the view model).
      this.#catalogCache.set(tab.id, this.items);
    }
    this.triggerDebouncedPush();
  }

  /** `comics8.desktop.ui.settings` viewer controls: `setViewMode`. */
  setViewMode(mode: ViewMode): void {
    this.viewMode = mode;
    this.#persistReaderSetting();
  }

  /** `DesktopViewModel.setReadDirection`. */
  setReadDirection(direction: ReadDirection): void {
    this.readDirection = direction;
    this.#persistReaderSetting();
  }

  /** `DesktopViewModel.setSplitMode`. */
  setSplitMode(mode: SplitMode): void {
    this.splitMode = mode;
    this.#persistReaderSetting();
  }

  /**
   * `DesktopViewModel.recordImageAspectRatio(index, width, height)`.
   *
   * The reader calls this from every `<img onload>`; the ratio is what turns a paged mode into
   * smart wide-spread / slice layout (`buildDualSpreads`, `buildSinglePageSlices`). `ReaderDomain
   * .imageAspectRatio` returns `null` for a broken bitmap, which is dropped rather than stored.
   */
  recordImageAspectRatio(index: number, width: number, height: number): void {
    if (!Number.isFinite(index) || index < 0) return;
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return;
    const key = Math.trunc(index);
    const ratio = width / height;
    if (this.imageAspectRatios[key] === ratio) return;
    this.imageAspectRatios = { ...this.imageAspectRatios, [key]: ratio };
  }

  /**
   * Authoritative episode total of a work (`sourceId:toonId`), or `null` when nothing is known.
   *
   * `this.catalogTotals` is the counted row (`EpisodeListingTotal.count`, written by
   * {@link fetchEpisodeMetadata}); `totalEpisodesByWork` also carries a total the plugin itself
   * reported on a page. The series header reads this, so both surfaces answer with one number.
   */
  totalEpisodesOf(workKey: string): number | null {
    // A total the plugin itself reported outranks a count derived from the listing
    // ({@link EPISODE_TOTAL_RANK}): the source's own number cannot be improved by arithmetic.
    if (this.#episodeTotalRanks[workKey] !== EPISODE_TOTAL_RANK.reported) {
      const counted = this.catalogTotals[workKey]?.count;
      if (typeof counted === "number" && Number.isFinite(counted) && counted > 0) {
        return Math.trunc(counted);
      }
    }
    const total = this.totalEpisodesByWork[workKey];
    if (typeof total !== "number" || !Number.isFinite(total)) return null;
    return total > 0 ? Math.trunc(total) : null;
  }

  /** `DesktopViewModel.toggleFullscreen`. */
  toggleFullscreen(): void {
    this.isFullscreen = !this.isFullscreen;
  }

  /**
   * `DesktopViewModel.savePage(page)`: updates the in-memory row immediately and debounces the
   * history write (the desktop `pageSaveJob` uses the same 400 ms window).
   */
  savePage(page: number): void {
    const series = this.series;
    const episode = this.currentEpisode;
    const safePage = Math.max(0, Math.trunc(page));
    if (episode) {
      this.currentEpisode = { ...episode, lastReadPage: safePage };
    }
    if (!series || !this.currentEpisode) return;

    const sourceId = this.#sourceIdFor(series);
    this.#pendingPageSave = {
      sourceId,
      toonId: series.id,
      episode: this.currentEpisode,
      page: safePage,
      total: this.readerImages.length,
    };
    this.#pageSaveTimer = clearTimeoutOf(this.#pageSaveTimer);
    this.#pageSaveTimer = setTimeout(() => {
      this.#pageSaveTimer = null;
      void this.#flushPageSave();
    }, PAGE_SAVE_DEBOUNCE_MS);
  }

  /** Clears a queued page write and any pending toast; call when the shell unmounts. */
  destroy(): void {
    void this.#flushPageSave();
    this.#toastTimer = clearTimeoutOf(this.#toastTimer);
  }

  /** Shows a transient reader toast (the desktop `readerToastMessage` + `readerToastJob` pair). */
  showToast(message: string, durationMs = 1000): void {
    this.readerToastMessage = message;
    this.#toastTimer = clearTimeoutOf(this.#toastTimer);
    this.#toastTimer = setTimeout(() => {
      this.#toastTimer = null;
      this.readerToastMessage = null;
    }, durationMs);
  }

  // --- history ------------------------------------------------------------------------------------

  /** `DesktopViewModel.openSeriesFromHistory(record)`: opens the series, back returns to History. */
  async openSeriesFromHistory(record: HistoryRecord): Promise<void> {
    this.#returnScreen = "History";
    await this.openSeries(toonItemFromHistory(record));
  }

  /**
   * `DesktopUiState.progressLabel(history)` — the badge of one history row.
   *
   * The mode comes from the source (`ProgressDisplay` in the plugin manifest); the number comes from
   * `catalogTotals`, which {@link fetchEpisodeMetadata} only ever writes from a real count.
   */
  progressLabel(record: HistoryRecord): string {
    const key = workKey(record.sourceId, record.toonId);
    const mode = this.progressDisplayMode(record.sourceId);
    const order = record.lastReadOrder ?? this.#lastReadOrder[key] ?? 0;
    const total =
      this.catalogTotals[key] ??
      (record.totalEpisodes
        ? {
            count: record.totalEpisodes,
            lastPage: 1,
            pageSize: record.totalEpisodes,
            delta: 0,
          }
        : undefined);
    return (
      formatProgressBadge(mode, order, total, this.readCounts[key] ?? 0) ?? ""
    );
  }

  /** `SourceRegistry.defaultProgressDisplayMode(sourceId)` from the loaded plugin's metadata. */
  progressDisplayMode(sourceId: string): ProgressDisplayMode {
    const declared = this.#sources.get(sourceId)?.meta?.progressDisplay;
    if (declared === "READ_COUNT") return "READ_COUNT";
    if (declared === "LAST_READ_ORDER") return "LATEST_EPISODE";
    return defaultProgressDisplayMode(sourceId);
  }

  /** `DesktopViewModel.openHistory` (loads the rows, then routes to the History screen). */
  async openHistory(): Promise<void> {
    await this.loadHistory();
    this.screen = "History";
  }

  /** `DesktopViewModel.closeHistory`. */
  closeHistory(): void {
    this.screen = "Browse";
  }

  // --- downloads (`DesktopViewModel.openDownloads` … `cancelDownloads`) --------------------------

  /**
   * `DesktopViewModel.openDownloads`: cancels the pending reader/episode jobs, routes to the
   * `Downloads` screen and loads the manifest.
   */
  openDownloads(): void {
    if (!this.writesDownloads) return;
    this.#readerToken += 1;
    this.#episodeToken += 1;
    this.screen = "Downloads";
    this.downloadLoading = true;
    void this.loadDownloads();
  }

  /** `DesktopViewModel.closeDownloads`: the screen sits above Browse, so back lands there. */
  closeDownloads(): void {
    this.screen = "Browse";
  }

  /** `DesktopViewModel.loadDownloads`: mirrors the manager's summaries into the read model. */
  async loadDownloads(): Promise<void> {
    await this.#downloads.init();
    this.downloadSummaries = this.#downloads.getSummaries();
    this.downloadLoading = false;
  }

  /**
   * `DesktopViewModel.openDownloadOptions`: opens the dialog and fills `downloadCatalog`.
   *
   * Kotlin loads *every* episode of the series (`repository.loadAllEpisodes`), which is what makes
   * "전체 다운로드" mean the whole work and not the page on screen. The web twin walks the listing
   * pages the same way and falls back to the loaded page, exactly like the Kotlin `catch` branch.
   */
  openDownloadOptions(): void {
    if (!this.writesDownloads) return;
    const series = this.series;
    if (!series) return;
    this.showDownloadOptions = true;
    this.downloadCatalog = [];
    this.downloadCatalogLoading = true;
    void this.#loadDownloadCatalog(series);
  }

  /** `DesktopViewModel.closeDownloadOptions`. */
  closeDownloadOptions(): void {
    this.showDownloadOptions = false;
    this.downloadCatalog = [];
    this.downloadCatalogLoading = false;
  }

  /** `DesktopViewModel.startDownloadEpisodes(episodesToDownload)`. */
  startDownloadEpisodes(episodesToDownload: readonly DesktopEpisodeItem[]): void {
    if (!this.writesDownloads) return;
    const series = this.series;
    if (!series) return;
    this.closeDownloadOptions();
    this.#downloads.enqueueEpisodes(
      series,
      episodesToDownload,
      series.sourceId ?? this.activeSourceId ?? "local",
    );
  }

  /** `DesktopViewModel.startDownloadEpisode(episode)` — the card's per-chapter button. */
  startDownloadEpisode(episode: DesktopEpisodeItem): void {
    this.startDownloadEpisodes([episode]);
  }

  /** `DesktopViewModel.cancelDownloads` (`DownloadQueueEngine.cancelAll`). */
  cancelDownloads(): void {
    this.#downloads.cancelAll();
  }

  /** `DesktopViewModel.deleteToonDownloads(workId)` — the trash button of the downloads pane. */
  async deleteToonDownloads(sourceId: string, toonId: string): Promise<void> {
    await this.#downloads.init();
    await this.#downloads.deleteToonDownloads(downloadedWorkKey(sourceId, toonId));
    this.downloadSummaries = this.#downloads.getSummaries();
    const key = this.#openWorkKey();
    if (key === downloadedWorkKey(sourceId, toonId)) {
      this.downloadedWrIds = new Set();
    }
    await this.loadDownloads();
  }

  /** `DesktopViewModel.refreshDownloadedWrIds` — the chapter ids of the open series. */
  async refreshDownloadedWrIds(): Promise<void> {
    const key = this.#openWorkKey();
    if (!key) {
      this.downloadedWrIds = new Set();
      return;
    }
    await this.#downloads.init();
    this.downloadedWrIds = this.#downloads.getDownloadedWrIds(key);
  }

  /** Name of the folder selected for downloads, or null if not yet selected. */
  get downloadFolderName(): string | null {
    return this.#downloads.downloadFolderName;
  }

  /** Prompts the user to select a download folder via the File System Access API. */
  async promptDownloadFolder(): Promise<boolean> {
    return this.#downloads.promptDownloadFolder();
  }

  /** `WorkId.storageKey()` of the open series, or `null` when no series is open. */
  #openWorkKey(): string | null {
    const series = this.series;
    if (!series) return null;
    return downloadedWorkKey(series.sourceId ?? this.activeSourceId ?? "local", series.id);
  }

  /**
   * `DesktopToonRepository.loadAllEpisodes(series)`: every chapter, across every listing page.
   *
   * A failed sweep keeps what already arrived and falls back to the loaded pages, which is the
   * Kotlin `catch` branch (`downloadCatalog = rawEpisodes.ifEmpty { episodes }`).
   */
  async #loadDownloadCatalog(series: DesktopToonItem): Promise<void> {
    const sourceId = series.sourceId ?? this.activeSourceId ?? null;
    const source = await this.#requireSource(sourceId);
    if (!source) {
      this.downloadCatalog = this.episodes.length > 0 ? this.episodes : this.rawEpisodes;
      this.downloadCatalogLoading = false;
      return;
    }

    const pageSize = Math.max(1, Math.trunc(source.meta?.episodePageSize ?? 100));
    const collected: DesktopEpisodeItem[] = [];
    try {
      let page = 1;
      let lastPage = 1;
      do {
        const result = await source.loadEpisodes(series, page);
        const info = normalisePageInfo(result, page);
        lastPage = Math.max(1, Math.trunc(info.lastPage));
        for (const episode of result?.items ?? []) {
          collected.push({ ...episode });
        }
        page += 1;
        // A plugin that keeps answering with items but never moves `lastPage` would loop forever;
        // the page size is the only bound that also holds for a mis-declared listing.
        if (page > Math.max(lastPage, Math.ceil(collected.length / pageSize) + 1)) break;
      } while (page <= lastPage);
      this.downloadCatalog = dedupeEpisodeList(collected);
    } catch {
      this.downloadCatalog =
        collected.length > 0
          ? dedupeEpisodeList(collected)
          : this.rawEpisodes.length > 0
            ? this.rawEpisodes
            : this.episodes;
    } finally {
      this.downloadCatalogLoading = false;
    }
  }

  /** `source.resolveImages(episode, series)` for the download queue. */
  async #resolveDownloadImages(
    sourceId: string,
    episode: DesktopEpisodeItem,
    series: ToonItem,
  ): Promise<string[]> {
    const source = await this.#requireSource(sourceId);
    if (!source) return [];
    return source.resolveImages(episode, series);
  }

  /** `DesktopViewModel.openSettings`: leaves the reader/series underneath (`screen = Settings`). */
  openSettings(): void {
    if (this.screen === "Reader") void this.#flushPageSave();
    // `_state.update { it.copy(screen = Screen.Settings, selectedSettingsCategory = null) }`
    this.screen = "Settings";
    this.selectedSettingsCategory = null;
  }

  /**
   * `DesktopViewModel.selectSettingsCategory(category)`.
   *
   * `null` returns to the hub; any other value routes the pane to that sub-screen.
   */
  selectSettingsCategory(category: SettingsCategory | null): void {
    this.selectedSettingsCategory = category;
  }

  /**
   * `DesktopViewModel.closeSettings` — one step back, not all the way out.
   *
   * The Kotlin action pops the category first and only leaves the settings screen from the hub, so
   * the pane's back arrow and the top bar's back button behave differently on purpose.
   */
  closeSettings(): void {
    if (this.selectedSettingsCategory !== null) {
      this.selectedSettingsCategory = null;
    } else {
      this.screen = "Browse";
    }
  }

  /** The sub-pane's own back arrow (`navBackToList`): always returns to the category hub. */
  closeSettingsCategory(): void {
    this.selectSettingsCategory(null);
  }

  // --- settings: general (language) --------------------------------------------------------------

  /** `DesktopViewModel.setAppLanguage`: `prefs.put("app_language", language.code)`. */
  setAppLanguage(language: AppLanguage): void {
    this.appLanguage = language;
    void this.#persistPreferencesNow();
  }

  // --- settings: sync ----------------------------------------------------------------------------

  /** Copies {@link SyncClient.getStatus} into {@link syncState} (`syncState.value = …` on desktop). */
  refreshSyncState(status: SyncStatus = this.#syncClient.getStatus()): void {
    this.syncState = {
      isSyncing: this.syncState.isSyncing,
      // `SyncState.isSuccess` is nullable: `idle` means "no attempt yet", not "failed".
      isSuccess:
        status.lastResult === "success" ? true : status.lastResult === "error" ? false : null,
      lastSyncedAt: status.lastSyncedAt,
      syncKey: status.syncKey,
      serverUrl: status.serverUrl,
      autoSyncEnabled: status.autoSyncEnabled,
      isPro: status.isPro,
      syncMessage: status.lastResult === "error" ? status.lastError : null,
    };
  }

  /** Hydrates {@link syncState} from persisted credentials (idempotent, like `SyncClient.init`). */
  async loadSyncState(): Promise<void> {
    try {
      this.refreshSyncState(await this.#syncClient.init());
    } catch (cause) {
      this.syncState = {
        ...this.syncState,
        isSuccess: false,
        syncMessage: describeError(cause, "동기화 상태를 불러오지 못했습니다."),
      };
    }
  }

  /**
   * `BaseSyncManager.triggerDebouncedPush`: delays 2.5s and silently pushes local changes
   * to the sync server when autoSync is enabled and syncKey is present.
   */
  triggerDebouncedPush(): void {
    if (!this.syncState.autoSyncEnabled || !this.syncState.syncKey) return;
    if (this.#syncDebounceTimer) {
      clearTimeout(this.#syncDebounceTimer);
    }
    this.#syncDebounceTimer = setTimeout(() => {
      this.#syncDebounceTimer = null;
      if (this.syncState.isSyncing) return;
      void this.syncNow().catch(() => {
        // Silent opportunistic push
      });
    }, 2500);
  }

  /**
   * `DesktopViewModel.syncNow` → `SyncActions.syncNow`, and the `SyncDialog` pull-then-push pair.
   *
   * The pull is not optional: `import_snapshot_for_user` rewrites `lastReadOrder` /
   * `totalEpisodes` / `next*` on every push, and the web history row does not model them, so
   * {@link mergeRemoteHistory} carries the server's values forward.
   */
  async syncNow(): Promise<{
    ok: boolean;
    message: string;
    favorites: number;
    history: number;
    readEpisodes: number;
  }> {
    if (this.syncState.isSyncing) {
      return { ok: false, message: "이미 동기화가 진행 중입니다.", favorites: 0, history: 0, readEpisodes: 0 };
    }
    this.syncState = { ...this.syncState, isSyncing: true, syncMessage: null };
    try {
      await this.#syncClient.init();
      await Promise.all([
        favoritesStore.init().catch(() => undefined),
        historyStore.init().catch(() => undefined),
      ]);

      let remotePull: PullResult | null = null;
      try {
        remotePull = await this.#syncClient.pull();
      } catch {
        // Unknown account (nothing pushed yet) or a transient read failure: the push still goes
        // out, and every counter we would have carried forward is genuinely absent server-side.
      }

      if (remotePull) {
        await Promise.all([
          favoritesStore.applyRemoteFavorites(remotePull.favorites),
          historyStore.applyRemoteHistory(remotePull.history, remotePull.readEpisodes),
        ]);
        if (remotePull.catalog && remotePull.catalog.length > 0) {
          await catalogStore.saveAllCatalog(
            remotePull.catalog.map((c) => ({
              sourceId: c.sourceId,
              toonId: c.toonId,
              count: c.totalEpisodes,
              total: {
                count: c.totalEpisodes,
                lastPage: c.lastPage ?? 1,
                pageSize: c.pageSize ?? c.totalEpisodes,
                delta: c.delta ?? 0,
              },
            })),
          );
        }
        const historyRows = historyStore.getHistory();
        const catalogUpdates = [];
        for (const h of historyRows) {
          const k = workKey(h.sourceId, h.toonId);
          if (h.lastReadOrder) {
            this.#lastReadOrder[k] = Math.max(this.#lastReadOrder[k] ?? 0, h.lastReadOrder);
          }
          if (h.totalEpisodes && !this.catalogTotals[k]) {
            catalogUpdates.push({
              sourceId: h.sourceId,
              toonId: h.toonId,
              count: h.totalEpisodes,
            });
          }
        }
        if (catalogUpdates.length > 0) {
          await catalogStore.saveAllCatalog(catalogUpdates);
        }
        this.catalogTotals = mapCatalogTotals(catalogStore.getAll());
        if (this.items.length > 0) {
          this.items = this.#decorateItems(
            this.items,
            this.activeTab?.sourceId ?? this.activeSourceId ?? LOCAL_SOURCE,
          );
        }
        if (this.screen === "History") {
          await this.loadHistory();
        }
        if (this.series) {
          const sId = this.#sourceIdFor(this.series);
          this.seriesHistory = historyStore.getProgress(sId, this.series.id);
          this.seriesFavorited = favoritesStore.isFavorite(this.series.id, sId);
        }
      }

      const history = historyStore.getHistory();
      const updates = buildSyncUpdates({
        favorites: favoritesStore.getFavorites(),
        history,
        readEpisodes: historyStore.readEpisodes,
      });
      if (remotePull?.history) {
        updates.history = mergeRemoteHistory(history, remotePull.history);
      }

      const pushed = await this.#syncClient.push(updates);
      this.syncState = {
        ...this.syncState,
        isSyncing: false,
        isSuccess: true,
        lastSyncedAt: this.#syncClient.getStatus().lastSyncedAt || Date.now(),
        syncKey: this.#syncClient.getStatus().syncKey,
        isPro: pushed.isPro,
        syncMessage: `동기화 완료 — 즐겨찾기 ${pushed.favorites} · 기록 ${pushed.history} · 읽은 회차 ${pushed.readEpisodes}`,
      };
      return {
        ok: true,
        message: this.syncState.syncMessage ?? "",
        favorites: pushed.favorites,
        history: pushed.history,
        readEpisodes: pushed.readEpisodes,
      };
    } catch (cause) {
      const message = describeError(cause, "동기화에 실패했습니다.");
      this.refreshSyncState();
      this.syncState = { ...this.syncState, isSyncing: false, isSuccess: false, syncMessage: message };
      return { ok: false, message, favorites: 0, history: 0, readEpisodes: 0 };
    }
  }

  /** `DesktopViewModel.requestPairingCode` — mints the 6-digit PIN of `POST /pair/request`. */
  async requestPairingCode(): Promise<{ code: string; expiresInSeconds: number }> {
    await this.#syncClient.init();
    try {
      const result = await this.#syncClient.requestPairingCode();
      this.refreshSyncState();
      return { code: result.code, expiresInSeconds: result.expiresInSeconds };
    } catch (cause) {
      this.refreshSyncState();
      throw cause instanceof Error
        ? cause
        : new Error(describeError(cause, "페어링 코드 발급 실패"));
    }
  }

  /** `DesktopViewModel.confirmPairingCode` — `POST /pair/confirm`, then a sync like the desktop. */
  async confirmPairingCode(code: string): Promise<{ syncKey: string }> {
    await this.#syncClient.init();
    const result = await this.#syncClient.confirmPairingCode(code);
    this.refreshSyncState();
    void this.syncNow();
    return result;
  }

  /** `DesktopViewModel.updateSyncKey` — installs a master recovery key, then syncs. */
  async updateSyncKey(key: string): Promise<void> {
    await this.#syncClient.init();
    this.refreshSyncState(await this.#syncClient.setSyncKey(key));
    void this.syncNow();
  }

  /** `DesktopViewModel.restoreWithMasterKey` — installs master key and performs full sync. */
  async restoreWithMasterKey(key: string): Promise<{
    ok: boolean;
    message: string;
    favorites: number;
    history: number;
    readEpisodes: number;
  }> {
    await this.updateSyncKey(key);
    return this.syncNow();
  }

  /** `DesktopViewModel.generateNewSyncKey`. */
  async generateNewSyncKey(): Promise<void> {
    await this.#syncClient.init();
    this.refreshSyncState(await this.#syncClient.generateNewKey());
  }

  /** `DesktopViewModel.updateSyncServerUrl`. */
  async updateSyncServerUrl(url: string): Promise<void> {
    await this.#syncClient.init();
    this.refreshSyncState(await this.#syncClient.setServerUrl(url));
  }

  /** `DesktopViewModel.toggleAutoSync`. */
  async toggleAutoSync(enabled: boolean): Promise<void> {
    await this.#syncClient.init();
    this.refreshSyncState(await this.#syncClient.setAutoSyncEnabled(enabled));
  }

  // --- settings: network -------------------------------------------------------------------------

  /** `DesktopViewModel.setProxyType`: persists and drops the stale test result. */
  setProxyType(proxyType: ProxyType): void {
    this.networkSettings = { proxyType, customProxy: { ...this.networkSettings.customProxy } };
    this.proxyTestResult = null;
    this.proxyTestSuccess = null;
    void this.#persistPreferencesNow();
  }

  /** `DesktopViewModel.setCustomProxy`. */
  setCustomProxy(customProxy: CustomProxyConfig): void {
    this.networkSettings = { ...this.networkSettings, customProxy };
    this.proxyTestResult = null;
    this.proxyTestSuccess = null;
    void this.#persistPreferencesNow();
  }

  /**
   * `DesktopViewModel.testProxyConnection`.
   *
   * A browser extension cannot open a raw HTTP/SOCKS socket, so the reachability check is a request
   * through the background worker to the configured server (or the sync server for `SERVER`). That
   * still proves what the Kotlin action proves for the user: the path out of the device works.
   */
  async testProxyConnection(): Promise<{ ok: boolean; message: string }> {
    if (this.proxyTesting) return { ok: false, message: "연결 테스트가 진행 중입니다." };
    this.proxyTesting = true;
    this.proxyTestResult = null;
    this.proxyTestSuccess = null;

    const settings = this.networkSettings;
    const target =
      settings.proxyType === "CUSTOM_HTTP" || settings.proxyType === "CUSTOM_SOCKS"
        ? settings.customProxy.host.trim()
        : "";

    try {
      if (target === "") {
        // Nothing custom to dial: the device's own route is what `DIRECT` means, and there is no
        // proxy to dial for `SERVER` either (the server does the proxying for the native clients).
        await Promise.resolve();
        const message =
          settings.proxyType === "DIRECT"
            ? "직접 연결: 기기의 네트워크로 사이트에 접속합니다."
            : "서버 IP 경유: 메타데이터 요청이 동기화 서버를 경유합니다.";
        this.proxyTesting = false;
        this.proxyTestResult = message;
        this.proxyTestSuccess = true;
        return { ok: true, message };
      }

      const url = /^https?:\/\//i.test(target) ? target : `http://${target}`;
      const response = await backgroundFetch({ url, method: "GET", timeoutMs: 5000 });
      const message = response.error
        ? `프록시 연결 실패: ${response.error}`
        : `프록시 연결 성공 (HTTP ${response.status})`;
      this.proxyTestResult = message;
      this.proxyTestSuccess = response.error === undefined;
      return { ok: this.proxyTestSuccess === true, message };
    } catch (cause) {
      const message = describeError(cause, "프록시 연결 테스트에 실패했습니다.");
      this.proxyTestResult = message;
      this.proxyTestSuccess = false;
      return { ok: false, message };
    } finally {
      this.proxyTesting = false;
    }
  }

  /** `DesktopViewModel.openSyncDialog` (the cloud-sync modal of `ui/SyncDialog.svelte`). */
  openSyncDialog(): void {
    this.showSyncDialog = true;
  }

  /** `DesktopViewModel.closeSyncDialog`. */
  closeSyncDialog(): void {
    this.showSyncDialog = false;
  }

  /** `DesktopViewModel.loadHistory`: rows of the active source plus their read counts. */
  async loadHistory(): Promise<void> {
    await historyStore.init().catch(() => undefined);
    await catalogStore.init().catch(() => undefined);
    const sourceId = this.activeSourceId;
    this.historyLoading = this.historyItems.length === 0;
    const rows = sourceId
      ? historyStore.getHistory().filter((row) => row.sourceId === sourceId)
      : historyStore.getHistory();
    const counts: Record<string, number> = {};
    const orders: Record<string, number> = {};
    for (const row of rows) {
      const k = workKey(row.sourceId, row.toonId);
      counts[k] = this.#readCountOf(row.sourceId, row.toonId);
      if (row.lastReadOrder) {
        orders[k] = Math.max(orders[k] ?? 0, row.lastReadOrder);
      }
    }
    this.historyItems = rows;
    this.historyLoading = false;
    this.readCounts = { ...this.readCounts, ...counts };
    this.#lastReadOrder = { ...this.#lastReadOrder, ...orders };
  }

  /** `DesktopViewModel.clearAllHistory`: forgets the active source's progress rows. */
  async clearAllHistory(): Promise<void> {
    await historyStore.init().catch(() => undefined);
    const sourceId = this.activeSourceId;
    if (sourceId === null) return;
    const rows = historyStore.getHistory().filter((row) => row.sourceId === sourceId);
    for (const row of rows) {
      await historyStore.deleteHistory(row.sourceId, row.toonId);
    }
    this.historyItems = [];
    this.readCounts = {};
    this.seriesHistory = null;
    this.items = this.items.map((row) => ({ ...row, readProgress: null }));
    this.triggerDebouncedPush();
  }

  /**
   * `DesktopViewModel.deleteHistory(workId)`.
   *
   * The Kotlin action drops the row from every surface at once: the history list, the visible grid
   * (`readProgress = null`), `seriesHistory` when that series is open, and its read count.
   */
  async deleteHistory(record: HistoryRecord): Promise<void> {
    const sourceId = this.#sourceIdFor(record);
    await historyStore.deleteHistory(sourceId, record.toonId);
    const key = workKey(sourceId, record.toonId);
    this.historyItems = this.historyItems.filter(
      (row) => workKey(row.sourceId, row.toonId) !== key,
    );
    this.items = this.items.map((row) =>
      workKey(this.#sourceIdFor(row), row.id) === key ? { ...row, readProgress: null } : row,
    );
    if (this.series && workKey(this.#sourceIdFor(this.series), this.series.id) === key) {
      this.seriesHistory = null;
    }
    const { [key]: _count, ...restCounts } = this.readCounts;
    this.readCounts = restCounts;
    const { [key]: _order, ...restOrders } = this.#lastReadOrder;
    this.#lastReadOrder = restOrders;
    this.triggerDebouncedPush();
  }

  /**
   * `DesktopViewModel.continueHistoryEpisode(record)`.
   *
   * Opens the series, loads the episode page that holds the stored chapter (falling back to page 1
   * when a prediction misses), then opens that chapter at its saved page. A completed chapter
   * (`ReaderProgress.isCompleted`) moves on to the next newer episode instead of reopening it.
   */
  async continueHistoryEpisode(record: HistoryRecord): Promise<void> {
    if (this.screen === "History") this.#returnScreen = "History";
    const series = toonItemFromHistory(record);
    const sourceId = this.#sourceIdFor(series);
    const targetOrder = this.#lastReadOrder[workKey(sourceId, series.id)] ?? 0;

    this.screen = "Series";
    this.series = series;
    this.rawEpisodes = [];
    this.episodes = [];
    this.episodeSortOrder = this.#sortOrders[sourceId] ?? "DEFAULT";
    this.episodeLoading = true;
    this.highlightedEpisodeId = record.lastWrId;
    this.readerLoading = true;
    this.readerError = null;
    this.readerImages = [];
    this.imageAspectRatios = {};

    // `ReaderDomain.predictedEpisodePage(totalEpisodes, targetReadOrder)`.
    const counted = this.catalogTotals[workKey(sourceId, series.id)];
    // The counted row records the page size the total was measured against
    // (`DesktopToonRepository.sourceOrNull(item.sourceId).episodePageSize`); it outranks the active
    // source's declaration because the arithmetic below has to match the shape of the count.
    const pageSize = Math.max(
      1,
      counted?.pageSize ?? this.activeSource?.meta?.episodePageSize ?? 100,
    );
    const predictedPage =
      counted && counted.count > targetOrder && counted.count > pageSize
        ? Math.max(1, Math.trunc((counted.count - targetOrder) / pageSize) + 1)
        : 1;
    const startPage = clampPage(predictedPage, Math.max(1, this.episodeLastPage));

    await this.#loadEpisodes(series, startPage);
    let index = this.#navigationEpisodes.findIndex((row) => row.wrId === record.lastWrId);
    if (index < 0 && startPage !== 1) {
      await this.#loadEpisodes(series, 1);
      index = this.#navigationEpisodes.findIndex((row) => row.wrId === record.lastWrId);
    }
    this.episodeLoading = false;

    const stored = this.#navigationEpisodes[index] ?? null;
    const fallback: DesktopEpisodeItem = {
      wrId: record.lastWrId,
      title: record.lastEpisodeTitle,
      href: "",
      lastReadPage: 0,
    };
    const episode = stored ?? fallback;
    const completed = this.#isCompletedChapter(sourceId, record);

    if (!completed) {
      // `ReaderProgress.startPageOnOpen`: reopen where the user stopped.
      await this.openEpisode({ ...episode, lastReadPage: record.pageIndex });
      return;
    }
    const next =
      index > 0
        ? (this.#navigationEpisodes[index - 1] ?? null)
        : (this.#navigationEpisodes.find((row) => row.wrId !== record.lastWrId) ?? null);
    await this.openEpisode({ ...(next ?? episode), lastReadPage: 0 });
  }

  /**
   * `ReaderProgress.isCompleted` for a web history row.
   *
   * The web history store keeps a 0-based page index plus the chapter's page count instead of the
   * desktop's sign-encoded `lastPage`, so a chapter is finished when the stored index reached the
   * last page it had. A row with no page count yet is unfinished (the desktop's "no read record"
   * case cannot be resolved better without the page count).
   */
  #isCompletedChapter(sourceId: string, record: HistoryRecord): boolean {
    if (record.totalPages <= 0) return false;
    if (record.pageIndex < record.totalPages - 1) return false;
    return historyStore.readEpisodes.some(
      (row) =>
        row.sourceId === sourceId && row.toonId === record.toonId && row.wrId === record.lastWrId,
    );
  }

  // --- internals: sources & tabs -------------------------------------------------------------------

  /**
   * `DesktopToonRepository.installedSources()`: the registry list in the stored id order, narrowed to
   * the sources the user has switched on (`SourcePrefs.INSTALLED_KEY`).
   *
   * Entries the preference list does not know yet (a plugin added by hand, a registry that changed)
   * keep their registry order after the known ones, so nothing is ever hidden by a stale list.
   */
  #listSources(): SourceSummary[] {
    const listed = this.#registry.list();
    const byId = new Map(listed.map((source) => [source.id, source] as const));
    const ordered: SourceSummary[] = [];
    for (const id of parseIdList(this.#sourceOrder)) {
      const source = byId.get(id);
      if (!source) continue;
      ordered.push(source);
      byId.delete(id);
    }
    ordered.push(...byId.values());
    return ordered.filter((source) => this.isSourceEnabled(source.id));
  }

  /**
   * `applyActiveSource(WorkId.LOCAL_SOURCE, loadListing = true)` after the current source left.
   *
   * The local library is the desktop fallback; when the web shell has no local source to load (it
   * has no folder picker), the shell simply ends up with no active source instead of a stale id.
   */
  async #activateFallback(): Promise<void> {
    const fallback = this.#listSources()[0] ?? null;
    const loaded = fallback ? await this.#requireSource(fallback.id) : null;
    if (!loaded) {
      this.#activeSourcePref = null;
      void this.#persistPreferencesNow();
      this.#applySource(null);
      return;
    }
    await this.setActiveSource(loaded.id);
  }

  /** `private fun clearCatalogCache(sourceId)`: forgets the cached pages of one source. */
  #clearCatalogCache(sourceId: string): void {
    // Catalog keys are `browseTabId(sourceId, catalogId)` = `${sourceId}:${catalogId}`.
    const prefix = `${sourceId}:`;
    for (const key of [...this.#catalogCache.keys()]) {
      if (key.startsWith(prefix)) this.#catalogCache.delete(key);
    }
    for (const key of [...this.#catalogPages.keys()]) {
      if (key.startsWith(prefix)) this.#catalogPages.delete(key);
    }
  }

  async #requireSource(sourceId: string | null): Promise<AnyLoadedSource | null> {
    if (!sourceId) return null;
    const cached = this.#sources.get(sourceId);
    if (cached) return cached;
    const loaded = await this.#registry.get(sourceId).catch(() => null);
    if (!loaded) return null;
    this.#sources.set(sourceId, loaded);
    return loaded;
  }

  /** `applyActiveSource(sourceId, loadListing = false)`. */
  #applySource(source: AnyLoadedSource | null): void {
    this.#loadToken += 1;
    this.#episodeToken += 1;
    this.#readerToken += 1;
    const tabs = source ? browseTabsForSource(source) : [];
    const nextTab = afterSourceChange(this.activeTab, tabs);

    this.activeSourceId = source?.id ?? null;
    this.installedSources = this.#listSources();
    this.browseTabs = tabs;
    this.activeTab = nextTab;
    this.searchSuggestions = [];
    if (!source) {
      this.items = [];
      this.series = null;
      this.rawEpisodes = [];
      this.episodes = [];
      this.currentEpisode = null;
      this.readerImages = [];
      this.imageAspectRatios = {};
      this.screen = "Browse";
      this.loading = false;
      this.error = null;
      return;
    }
    this.episodeSortOrder = this.#sortOrders[source.id] ?? "DEFAULT";
  }

  /** Source id of an item: the stamp this store adds, `activeSourceId`, or the local default. */
  #sourceIdFor(item: { sourceId?: string | null; [key: string]: unknown }): string {
    const explicit = item.sourceId;
    if (typeof explicit === "string" && explicit.trim() !== "") return explicit;
    return this.activeSourceId ?? LOCAL_SOURCE;
  }

  /**
   * Adds `sourceId` / `isFavorite` / `readProgress` to listing cards.
   *
   * This is `DesktopToonRepository.applyFlags` for the browse grid: `applyListingFlags` writes
   * `isFavorite` (favorite lookup), `readProgress` (`formatReadProgress` against the counted total)
   * and keeps everything the plugin returned. The card therefore prints "52/64" once the user has
   * read up to chapter 52 and "64화" before that, instead of always being blank.
   */
  #decorateItems(
    list: readonly ToonItem[],
    sourceId: string,
    query?: { catalogTotals?: Record<string, CatalogTotal> },
  ): DesktopToonItem[] {
    const totals = query?.catalogTotals ?? this.catalogTotals;
    const mode = this.progressDisplayMode(sourceId);

    return list.map((item) => {
      const key = workKey(sourceId, item.id);
      const history = historyStore.getProgress(sourceId, item.id);
      const readOrder = history?.lastReadOrder ?? this.#lastReadOrder[key] ?? 0;
      const total =
        totals[key] ??
        (history?.totalEpisodes
          ? {
              count: history.totalEpisodes,
              lastPage: 1,
              pageSize: history.totalEpisodes,
              delta: 0,
            }
          : undefined);
      return {
        ...item,
        sourceId,
        isFavorite: favoritesStore.isFavorite(sourceId, item.id),
        readProgress: formatProgressBadge(
          mode,
          readOrder,
          total,
          this.#readCountOf(sourceId, item.id),
        ),
      };
    });
  }

  /**
   * `DesktopViewModel.loadItems()` for the browse grid: re-decorates the loaded cards with the
   * totals persisted in {@link catalogStore}.
   *
   * `DesktopToonRepository.applyFlags` batch-reads `toon_catalog` for the cards on screen, so a card
   * shows "64화" (nothing read yet) or "52/64" without the series having been opened in this
   * session. Both this and {@link #loadPage} / {@link search} then hand the grid to
   * {@link syncEpisodeMetadata}, which asks the server for the rows this device never counted.
   */
  async loadItems(): Promise<void> {
    const tab = this.activeTab;
    if (this.items.length === 0) return;
    const sourceId = tab?.sourceId ?? this.activeSourceId ?? LOCAL_SOURCE;
    const totals = await this.#mergeStoredTotals(sourceId, this.items);
    this.items = this.#decorateItems(this.items, sourceId, { catalogTotals: totals });
    // A caller that decorates an already-loaded grid still needs the rows this device never
    // counted: `syncEpisodeCounts` fills them from the server cache in the background.
    void this.syncEpisodeMetadata(this.items);
  }

  /**
   * `DesktopToonRepository.syncEpisodeCounts` (step 1): server-first batch query of the counted
   * totals this device never counted itself.
   *
   * A browse card can only print a badge once the work's total is known, and counting a listing
   * costs one request *per page*. Another client (Android/Desktop) has usually already counted it
   * and reported it to `POST /catalog/report`, so the missing rows are asked for in one batch and
   * written straight into {@link catalogTotals} + {@link catalogStore}. The grid is then re-decorated
   * so `readProgress` ("64화" before anything is read, "52/64" afterwards) appears immediately.
   *
   * Callers fire this without awaiting ({@link #loadPage}, {@link search}); a failed lookup answers
   * `[]` and leaves the badges blank, exactly like the Kotlin `catch (_: Exception) {}`.
   */
  async syncEpisodeMetadata(items: readonly ToonItem[]): Promise<void> {
    if (items.length === 0) return;
    const sourceOf = new Map<string, string>();
    for (const item of items) sourceOf.set(item.id, this.#sourceIdFor(item));

    // `missingFromLocal`: only works whose counted row is absent locally. A row already in
    // `catalogTotals` (this session, or a previous one through `catalogStore`) is not re-asked for.
    const missing = items.filter((item) => {
      const sourceId = this.#sourceIdFor(item);
      return !isCountedTotal(this.catalogTotals[workKey(sourceId, item.id)]);
    });
    if (missing.length === 0) return;

    // Library files never leave the device (`import_snapshot_for_user` rejects `local`), so there is
    // nothing server-side to ask about.
    const queries = missing.filter((item) => this.#sourceIdFor(item) !== LOCAL_SOURCE);
    if (queries.length === 0) return;

    const token = this.#loadToken;
    let rows: SyncCatalogRow[] = [];
    try {
      rows = await this.#syncClient.fetchCatalogBatch(
        queries.map((item) => ({ sourceId: this.#sourceIdFor(item), toonId: item.id })),
      );
    } catch {
      // A badge is a nicety; a failed batch must never surface as an error state.
      rows = [];
    }
    if (rows.length === 0) return;

    let changed = false;
    for (const row of rows) {
      const total = catalogTotalFromRow(row);
      if (!total) continue;
      // The server may answer under its canonical source id; the cards are keyed by ours
      // (`bindingManager.getLocalSourceId(wire.sourceId)` on the desktop).
      const sourceId = sourceOf.get(row.toonId) ?? row.sourceId;
      if (!sourceId) continue;
      const key = workKey(sourceId, row.toonId);
      const previous = this.catalogTotals[key];
      this.catalogTotals = { ...this.catalogTotals, [key]: toCatalogTotal(total) };
      this.#recordEpisodeTotal(key, total.count, EPISODE_TOTAL_RANK.counted);
      await catalogStore.saveCatalog(sourceId, row.toonId, total).catch(() => undefined);
      if (previous?.count !== total.count) changed = true;
    }
    if (!changed) return;
    // The listing may have been replaced while the batch was in flight; re-decorating a stale grid
    // would stamp another source's cards.
    if (token !== this.#loadToken) return;
    // `onUpdated` on the desktop rewrites the one row it learned about; the web grid re-decorates in
    // place so every card sharing that work (search hit, favorite tab) picks the badge up at once.
    this.items = this.items.flatMap((item) =>
      this.#decorateItems([item], this.#sourceIdFor(item)),
    );
  }

  /**
   * Batch-reads the persisted totals of `items` and merges them into {@link catalogTotals}.
   *
   * `DesktopDatabase.getCatalogTotalsByToonIds` reads the rows the cards need in one query; the web
   * store answers from memory after {@link init}. Rows that are not a real count are dropped by the
   * store itself, and a reported total already recorded keeps its higher rank.
   */
  async #mergeStoredTotals(
    sourceId: string,
    items: readonly ToonItem[],
  ): Promise<Record<string, CatalogTotal>> {
    if (items.length === 0) return this.catalogTotals;
    await catalogStore.init().catch(() => undefined);
    const stored = catalogStore.getCatalogsByToonIds(
      sourceId,
      items.map((item) => item.id),
    );
    if (Object.keys(stored).length === 0) return this.catalogTotals;
    const merged: Record<string, CatalogTotal> = { ...this.catalogTotals };
    for (const [key, total] of Object.entries(stored)) {
      merged[key] = toCatalogTotal(total);
      if (this.#episodeTotalRanks[key] === undefined) {
        this.#episodeTotalRanks[key] = EPISODE_TOTAL_RANK.counted;
        this.#recordEpisodeTotal(key, total.count, EPISODE_TOTAL_RANK.counted);
      }
    }
    this.catalogTotals = merged;
    return merged;
  }

  // --- internals: listing ---------------------------------------------------------------------------

  /** `loadPage(page, replace)`, including the `Favorite` tab's store-backed listing. */
  async #loadPage(page: number, options: { replace?: boolean } = {}): Promise<void> {
    const tab = this.activeTab;
    if (!tab) return;
    const target = Math.max(1, Math.trunc(page));
    const source = await this.#requireSource(this.activeSourceId);
    if (!source || this.activeTab?.id !== tab.id) return;

    const token = ++this.#loadToken;
    this.loading = !options.replace || this.items.length === 0;
    this.refreshing = options.replace === true && this.items.length > 0;
    this.error = null;

    try {
      if (tab.kind === "favorite") {
        await favoritesStore.init().catch(() => undefined);
        if (token !== this.#loadToken) return;
        const rows = favoritesStore
          .getFavorites()
          .filter((record) => record.sourceId === tab.sourceId)
          .map(favoriteToToon);
        this.#catalogCache.set(tab.id, rows);
        this.#catalogPages.set(tab.id, { page: 1, lastPage: 1, lastPageKnown: false });
        this.page = 1;
        this.lastPage = 1;
        this.lastPageKnown = false;
        // The favorite tab is a listing too: `applyFlags(items, touchFavorites = false)` still
        // renders the counted total of every row.
        const totals = await this.#mergeStoredTotals(tab.sourceId, rows);
        if (token !== this.#loadToken) return;
        this.items = this.#decorateItems(rows, tab.sourceId, { catalogTotals: totals });
        this.loading = false;
        this.refreshing = false;
        this.error = null;
        void this.syncEpisodeMetadata(this.items);
        return;
      }

      const result = await source.loadListing(tab.catalogId, target);
      if (token !== this.#loadToken) return;
      // The whole result is passed in: some plugins report the page keys on the listing itself
      // (`result.lastPage` / `result.maxPage` / `result.total_pages`) instead of `result.pageInfo`.
      const info = normalisePageInfo(result, target);
      const cards = dedupeSeries(result?.items ?? []);
      const totals = await this.#mergeStoredTotals(tab.sourceId, cards);
      if (token !== this.#loadToken) return;
      const decorated = this.#decorateItems(cards, tab.sourceId, { catalogTotals: totals });
      this.#catalogCache.set(tab.id, cards);
      this.#catalogPages.set(tab.id, {
        page: info.currentPage,
        lastPage: info.lastPage,
        lastPageKnown: info.lastPageKnown,
      });
      this.page = info.currentPage;
      this.lastPage = info.lastPage;
      this.lastPageKnown = info.lastPageKnown;
      this.items = decorated;
      this.loading = false;
      this.refreshing = false;
      this.error = null;
      // `DesktopViewModel.loadPage` ends with `syncEpisodeCounts(items)`: the cards that arrived
      // without a total ask the server cache for one, so their badges appear without opening the
      // series (and without a per-page count of every listing on screen).
      void this.syncEpisodeMetadata(this.items);
    } catch (cause) {
      if (token !== this.#loadToken) return;
      this.loading = false;
      this.refreshing = false;
      this.error = describeError(cause, "목록을 불러오지 못했습니다.");
    }
  }

  // --- internals: episodes ----------------------------------------------------------------------------

  /** `loadEpisodes(item, page)` plus the counted total (`fetchEpisodeMetadata`). */
  async #loadEpisodes(item: DesktopToonItem, page: number): Promise<void> {
    const sourceId = this.#sourceIdFor(item);
    const source = await this.#requireSource(sourceId);
    if (!source) {
      this.episodeLoading = false;
      this.episodeError = `소스 "${sourceId}"를 불러올 수 없습니다.`;
      return;
    }
    const target = Math.max(1, Math.trunc(page));
    const token = ++this.#episodeToken;
    const order = this.#sortOrders[sourceId] ?? this.episodeSortOrder;

    this.episodeSortOrder = order;
    this.episodeLoading = true;
    this.episodeError = null;

    try {
      const result = await source.loadEpisodes(item, target);
      if (token !== this.#episodeToken) return;
      const raw = dedupeEpisodeList((result?.items ?? []) as DesktopEpisodeItem[]);
      const info = normalisePageInfo(result, target);
      this.rawEpisodes = raw;
      this.episodes = sortEpisodes(raw, order);
      this.episodePage = info.currentPage;
      this.episodeLastPage = info.lastPage;
      this.episodeLoading = false;
      this.episodeError = null;
      // `DesktopViewModel.loadEpisodes` hands the page it just loaded to `fetchEpisodeMetadata`, so
      // the tail page is not requested twice. A reported total is recorded first because it needs no
      // arithmetic at all.
      this.#recordPageTotal(item, sourceId, result, info, raw);
      const metadata = await this.fetchEpisodeMetadata(item, {
        items: raw,
        currentPage: info.currentPage,
        lastPage: info.lastPage,
        reported: reportedEpisodeTotal(result),
      });
      // `DesktopViewModel.loadEpisodes` → `updateThumbnailIfBlank`: the listing can supply the cover a
      // listing card did not carry, and the episode page is the only place that knows it.
      if (metadata.thumbUrl) this.#applySeriesThumbnail(item, sourceId, metadata.thumbUrl);
    } catch (cause) {
      if (token !== this.#episodeToken) return;
      const keepLoaded = this.episodes.length > 0 || this.rawEpisodes.length > 0;
      this.episodeLoading = false;
      this.episodeError = keepLoaded
        ? null
        : describeError(cause, "회차 목록을 불러오지 못했습니다.");
    }
  }

  /**
   * `DesktopToonRepository.fetchEpisodeMetadata`: resolves the counted total of one work.
   *
   * The total only ever comes from counting the listing. A stored count whose shape still matches
   * (`EpisodeListingTotal.describes`) lets a single tail-page read refresh it through the learned
   * correction; a missing or mismatched shape means the listing is counted again, page by page.
   *
   * [knownPage] lets a caller that already fetched a page hand it over, so the tail page is not
   * requested twice when the series screen has just loaded it.
   *
   * Returns the counted total (`null` while the work is uncounted), and mirrors it into
   * {@link catalogTotals}, {@link catalogStore} and {@link totalEpisodesByWork}.
   */
  async fetchEpisodeMetadata(
    item: DesktopToonItem,
    knownPage?: KnownEpisodePage | null,
  ): Promise<EpisodeMetadata> {
    const sourceId = this.#sourceIdFor(item);
    const source = await this.#requireSource(sourceId);
    if (!source) return { total: null, thumbUrl: null };

    const pageSize = Math.max(
      1,
      Math.trunc(source.meta?.episodePageSize ?? knownPage?.items.length ?? ESTIMATE_PAGE_SIZE),
    );
    const key = workKey(sourceId, item.id);
    const needThumb = (item.thumbUrl ?? "").trim() === "";

    // A 1-page listing already has every episode in memory: no stored read and no extra fetch.
    if (knownPage && knownPage.lastPage <= 1 && knownPage.currentPage <= 1) {
      const singleTotal = totalFromPage(knownPage, pageSize, null);
      const thumb = needThumb ? firstThumb(knownPage.items) : null;
      if (singleTotal) await this.#saveEpisodeTotal(key, singleTotal, EPISODE_TOTAL_RANK.counted);
      return { total: singleTotal, thumbUrl: thumb };
    }

    const known = await this.#knownCatalogTotal(sourceId, item.id);
    // Pages read during this call, so a refresh never fetches the same page twice.
    const loaded = new Map<number, KnownEpisodePage>();
    if (knownPage) loaded.set(knownPage.currentPage, knownPage);

    const loadPage = async (number: number): Promise<EpisodePageShape> => {
      const cached = loaded.get(number);
      if (cached) return cached;
      const result = await source.loadEpisodes(item, number);
      const info = normalisePageInfo(result, number);
      const page: KnownEpisodePage = {
        items: dedupeEpisodeList((result?.items ?? []) as DesktopEpisodeItem[]),
        currentPage: info.currentPage,
        lastPage: info.lastPage,
        reported: reportedEpisodeTotal(result),
      };
      loaded.set(number, page);
      return page;
    };
    const thumbFrom = (number: number): string | null =>
      needThumb ? firstThumb(loaded.get(number)?.items ?? []) : null;

    // A total the plugin reported on the page it just answered with is authoritative: nothing can be
    // counted more accurately than the source's own number, so it is stored directly and no page
    // sweep is spent on a number the source already knows.
    const reported = knownPage?.reported ?? null;
    if (reported !== null) {
      this.#recordEpisodeTotal(key, reported, EPISODE_TOTAL_RANK.reported);
      const lastPage = Math.max(1, Math.trunc(knownPage?.lastPage ?? 1));
      if (lastPage === 1) {
        const row: EpisodeListingTotal = { count: reported, lastPage: 1, pageSize, delta: 0 };
        await this.#saveEpisodeTotal(key, row, EPISODE_TOTAL_RANK.reported);
        return { total: row, thumbUrl: needThumb ? firstThumb(knownPage?.items ?? []) : null };
      }
      // The stored correction is derived from the live tail page, so a later tail read reproduces
      // the reported number while an added/removed newest episode still moves it.
      const tail = await loadPage(lastPage).catch(() => null);
      const row: EpisodeListingTotal | null = tail
        ? {
            count: reported,
            lastPage,
            pageSize,
            delta: reported - ((lastPage - 1) * pageSize + tail.items.length),
          }
        : null;
      if (row && isValidEpisodeListingTotal(row)) {
        await this.#saveEpisodeTotal(key, row, EPISODE_TOTAL_RANK.reported);
        return { total: row, thumbUrl: thumbFrom(lastPage) ?? thumbFrom(1) };
      }
      // The tail page could not be read, so the count cannot be shaped: keep the reported number for
      // the header and let the sweep below store a verified row.
    }

    const shapeStillMatches =
      !knownPage || (known !== null && describesListingShape(known, knownPage.lastPage, pageSize));
    if (known !== null && shapeStillMatches) {
      // The tail page is read live: it holds the one value the correction does not cache (how many
      // episodes the tail page has), so an added episode moves the total even when the page count
      // stays the same.
      const tailPage = Math.max(1, Math.trunc(known.lastPage));
      let tail: EpisodePageShape | null = null;
      try {
        tail = await loadPage(tailPage);
      } catch {
        tail = null;
      }
      if (tail) {
        const reused = totalFromPage(tail, pageSize, known);
        if (reused) {
          if (needThumb && tailPage !== 1) {
            await loadPage(1).catch(() => null);
          }
          await this.#saveEpisodeTotal(key, reused, EPISODE_TOTAL_RANK.counted);
          return {
            total: reused,
            thumbUrl: thumbFrom(tailPage) ?? thumbFrom(1),
          };
        }
      }
    }

    // Uncounted, or the listing changed shape: count every page.
    const counted = await countEpisodeListing(pageSize, (number) => {
      // The page the caller handed over is reused instead of being fetched again.
      const cached = loaded.get(number);
      return cached ? cached : loadPage(number);
    });
    if (counted) {
      // The row is stored either way (it is the counted shape), but a reported number stays the
      // one {@link totalEpisodesOf} answers with, because its rank is higher.
      await this.#saveEpisodeTotal(key, counted, EPISODE_TOTAL_RANK.counted);
      if (needThumb && !loaded.has(1)) await loadPage(1).catch(() => null);
    }
    return { total: counted, thumbUrl: thumbFrom(1) };
  }

  /**
   * Reads the stored total of one work, hydrating from {@link catalogStore} on a miss.
   *
   * `DesktopDatabase.getCatalog` answers from SQLite; the web equivalent is the process-wide store,
   * whose rows were loaded once in {@link init}. A stored row that is not a valid count is dropped,
   * exactly like `getCatalogTotalsByToonIds`.
   */
  async #knownCatalogTotal(
    sourceId: string,
    toonId: string,
  ): Promise<EpisodeListingTotal | null> {
    const key = workKey(sourceId, toonId);
    const mirrored = this.catalogTotals[key];
    if (isCountedTotal(mirrored)) return mirrored;
    await catalogStore.init().catch(() => undefined);
    const stored = catalogStore.getCatalog(sourceId, toonId);
    if (!isValidEpisodeListingTotal(stored)) return null;
    this.catalogTotals = { ...this.catalogTotals, [key]: toCatalogTotal(stored) };
    // Re-read from storage (another tab may have written it): still a counted row, but
    // `totalEpisodesByWork` may already hold a total the plugin itself reported, which wins.
    if (this.#episodeTotalRanks[key] === undefined) {
      this.#episodeTotalRanks[key] = EPISODE_TOTAL_RANK.counted;
      this.#recordEpisodeTotal(key, stored.count, EPISODE_TOTAL_RANK.counted);
    }
    return stored;
  }

  /**
   * `DesktopViewModel.updateThumbnailIfBlank`: fills a blank cover from the episode listing.
   *
   * The card the user opened may have been stored without a thumbnail (favorites and history rows
   * can be older than the listing), so the first episode page that carries one is written onto the
   * open series and the grid row showing it.
   */
  #applySeriesThumbnail(item: DesktopToonItem, sourceId: string, thumbUrl: string): void {
    const key = workKey(sourceId, item.id);
    if (this.series && workKey(this.#sourceIdFor(this.series), this.series.id) === key) {
      if ((this.series.thumbUrl ?? "").trim() === "") {
        this.series = { ...this.series, thumbUrl };
      }
    }
    this.items = this.items.map((row) =>
      workKey(this.#sourceIdFor(row), row.id) === key && (row.thumbUrl ?? "").trim() === ""
        ? { ...row, thumbUrl }
        : row,
    );
  }

  /**
   * Persists a counted total and mirrors it into the reactive maps.
   *
   * `DesktopViewModel.updateHistoryAndProgress` → `repository.saveCatalogTotal` writes the row to
   * SQLite and the count to `catalogTotals`; here the row goes to {@link catalogStore}
   * (`chrome.storage.local`) and `totalEpisodesByWork` keeps the number the header prints.
   */
  async #saveEpisodeTotal(
    key: string,
    total: EpisodeListingTotal,
    rank: EpisodeTotalRank,
  ): Promise<void> {
    if (!isValidEpisodeListingTotal(total)) return;
    const [sourceId, ...rest] = key.split(":");
    const toonId = rest.join(":");
    if (!sourceId || !toonId) return;
    this.catalogTotals = { ...this.catalogTotals, [key]: toCatalogTotal(total) };
    this.#recordEpisodeTotal(key, total.count, rank);
    // No stamp: `CatalogStore` owns `updatedAt`, and it skips the write entirely when the row is
    // unchanged, so a tail-page refresh that reproduces the same count is not a new observation.
    await catalogStore
      .saveCatalog(sourceId, toonId, {
        count: total.count,
        lastPage: total.lastPage,
        pageSize: total.pageSize,
        delta: total.delta,
      })
      .catch(() => undefined);
  }

  /**
   * Records what the loaded page itself says about the total.
   *
   * A reported total is authoritative and outranks the counted row. Without one, the page shape is
   * the only evidence left: a single page is the whole listing, and a paginated listing is estimated
   * as `lastPage * pageSize` ({@link ESTIMATE_PAGE_SIZE} when the page came back empty). The estimate
   * is rank `estimate`, so the count that {@link fetchEpisodeMetadata} stores right after replaces it
   * and a reported number never loses.
   */
  #recordPageTotal(
    item: DesktopToonItem,
    sourceId: string,
    result: unknown,
    info: PageInfo,
    items: readonly DesktopEpisodeItem[],
  ): void {
    const key = workKey(sourceId, item.id);
    const reported = reportedEpisodeTotal(result);
    if (reported !== null) {
      this.#recordEpisodeTotal(key, reported, EPISODE_TOTAL_RANK.reported);
      return;
    }

    const pageSize = Math.max(1, items.length || ESTIMATE_PAGE_SIZE);
    const estimated = info.lastPage > 1 ? info.lastPage * pageSize : items.length;
    // Paging to the (short) last page must not shrink a multi-page estimate, so an estimate is
    // only ever raised. A counted or reported number still replaces it outright.
    if (
      info.lastPage > 1 &&
      (this.#episodeTotalRanks[key] ?? EPISODE_TOTAL_RANK.estimate) === EPISODE_TOTAL_RANK.estimate
    ) {
      const current = this.totalEpisodesByWork[key];
      if (typeof current === "number" && current > estimated) return;
    }
    this.#recordEpisodeTotal(
      key,
      estimated,
      info.lastPage > 1 ? EPISODE_TOTAL_RANK.estimate : EPISODE_TOTAL_RANK.counted,
    );
  }

  /** Writes `totalEpisodesByWork[key]` unless a more authoritative number already sits there. */
  #recordEpisodeTotal(key: string, total: number | null, rank: EpisodeTotalRank): void {
    if (total === null || !Number.isFinite(total) || total <= 0) return;
    const value = Math.trunc(total);
    const previous = this.#episodeTotalRanks[key];
    if (previous !== undefined && previous > rank) return;
    this.#episodeTotalRanks[key] = rank;
    if (this.totalEpisodesByWork[key] === value) return;
    this.totalEpisodesByWork = { ...this.totalEpisodesByWork, [key]: value };
  }

  // --- internals: reader -------------------------------------------------------------------------------

  async #loadReaderImages(
    episode: DesktopEpisodeItem,
    series: DesktopToonItem | null,
  ): Promise<void> {
    const sourceId = series ? this.#sourceIdFor(series) : this.activeSourceId;
    const token = this.#readerToken;
    const source = await this.#requireSource(sourceId);
    if (!source || !series) {
      this.readerLoading = false;
      this.readerError = "작품 정보를 찾을 수 없습니다.";
      return;
    }
    try {
      const images = await source.resolveImages(episode, series);
      if (token !== this.#readerToken) return;
      this.readerImages = Array.isArray(images) ? images : [];
      this.imageAspectRatios = {};
      this.readerLoading = false;
      this.readerError = null;
    } catch (cause) {
      if (token !== this.#readerToken) return;
      this.readerImages = [];
      this.imageAspectRatios = {};
      this.readerLoading = false;
      this.readerError = describeError(cause, "이미지를 불러오지 못했습니다.");
    }
  }

  async #writeProgress(
    series: DesktopToonItem,
    sourceId: string,
    episode: DesktopEpisodeItem,
    pageIndex: number,
  ): Promise<void> {
    const key = workKey(sourceId, series.id);
    const readOrder = this.#lastReadOrder[key] ?? 0;
    const totalEpisodes = this.catalogTotals[key]?.count ?? 0;
    await historyStore.recordProgress(
      sourceId,
      series.id,
      series.title,
      series.thumbUrl ?? "",
      episode,
      pageIndex,
      this.readerImages.length,
      readOrder,
      totalEpisodes,
    );
    this.#countReadEpisodes(sourceId, series.id);
  }

  /** Writes the debounced page turn (the desktop `flushPendingPageSave`). */
  async #flushPageSave(): Promise<void> {
    if (this.#pageSaveTimer) {
      clearTimeout(this.#pageSaveTimer);
      this.#pageSaveTimer = null;
    }
    const pending = this.#pendingPageSave;
    this.#pendingPageSave = null;
    if (!pending) return;
    const series = this.series;
    if (!series || series.id !== pending.toonId) return;
    const key = workKey(pending.sourceId, pending.toonId);
    const readOrder = this.#lastReadOrder[key] ?? 0;
    const totalEpisodes = this.catalogTotals[key]?.count ?? 0;
    await historyStore.recordProgress(
      pending.sourceId,
      pending.toonId,
      series.title,
      series.thumbUrl ?? "",
      pending.episode,
      pending.page,
      pending.total,
      readOrder,
      totalEpisodes,
    );
    this.triggerDebouncedPush();
  }

  async #applyNavigation(navigation: EpisodeNavigation): Promise<void> {
    if (navigation.kind === "in-current-page") {
      const target = this.#navigationEpisodes[navigation.index];
      if (target) await this.openEpisode(target);
      return;
    }
    if (navigation.kind === "load-page") {
      const series = this.series;
      if (!series) return;
      await this.#loadEpisodes(series, navigation.page);
      const list = this.#navigationEpisodes;
      const target = navigation.edge === "FIRST" ? list[0] : list[list.length - 1];
      if (target) await this.openEpisode(target);
    }
  }

  #persistReaderSetting(): void {
    const series = this.series;
    if (!series) return;
    const sourceId = this.#sourceIdFor(series);
    this.#readerSettings[workKey(sourceId, series.id)] = {
      viewMode: this.viewMode,
      readDirection: this.readDirection,
      splitMode: this.splitMode,
    };
    void this.#persistPreferencesNow();
  }

  // --- internals: counts & preferences -------------------------------------------------------------------

  #readCountOf(sourceId: string, toonId: string): number {
    return historyStore.readEpisodes.filter(
      (row) => row.sourceId === sourceId && row.toonId === toonId,
    ).length;
  }

  #countReadEpisodes(sourceId: string, toonId: string): void {
    const key = workKey(sourceId, toonId);
    this.readCounts = { ...this.readCounts, [key]: this.#readCountOf(sourceId, toonId) };
  }

  /**
   * Learns `ReadHistoryRecord.lastReadOrder` for the chapter just opened.
   *
   * `DesktopViewModel.updateHistoryAndProgress` derives the order from the *counted* total and the
   * loaded page (`ReaderDomain.episodePosition`); an uncounted work yields `null`, and the order is
   * then left alone rather than guessed from the page index. Orders only ever move forward for the
   * same reason the Kotlin `shouldElevate` check exists: re-opening an older chapter must not
   * erase a position the reader had already reached.
   */
  #learnReadOrder(
    series: DesktopToonItem,
    sourceId: string,
    episode: DesktopEpisodeItem,
  ): void {
    const key = workKey(sourceId, series.id);
    const ids = this.#navigationEpisodes.map((row) => row.wrId);
    const counted = this.catalogTotals[key];
    const position = episodePosition(
      ids,
      episode.wrId,
      this.episodePage,
      this.episodeLastPage,
      counted,
      // `DesktopViewModel.updateHistoryAndProgress` passes the source's `episodePageSize`; the
      // counted row carries the exact value the total was measured with, so it wins when present.
      counted?.pageSize ?? this.activeSource?.meta?.episodePageSize ?? 100,
    );
    const order = position.readOrder;
    if (order === null) return;
    const previous = this.#lastReadOrder[key] ?? 0;
    const newOrder = Math.max(previous, order);
    this.#lastReadOrder = { ...this.#lastReadOrder, [key]: newOrder };
    const hist = historyStore.getProgress(sourceId, series.id);
    if (hist && (hist.lastReadOrder ?? 0) < newOrder) {
      hist.lastReadOrder = newOrder;
      if (counted?.count && !hist.totalEpisodes) {
        hist.totalEpisodes = counted.count;
      }
    }
  }

  async #loadPreferences(): Promise<void> {
    if (this.#prefsLoaded) return;
    this.#prefsLoaded = true;
    if (!this.#persistPreferences) return;
    const area = chromeStorageArea();
    if (!area) return;
    try {
      const stored = await area.get(DESKTOP_PREFS_KEY);
      const prefs = parsePrefs(stored?.[DESKTOP_PREFS_KEY]);
      this.#activeSourcePref = prefs.activeSourceId;
      this.#sortOrders = prefs.episodeSortOrders;
      this.#readerSettings = prefs.readerSettings;
      this.#sourceOrder = prefs.sourceOrder;
      this.#enabledSourceIds = prefs.enabledSourceIds;
      this.appLanguage = prefs.appLanguage;
      this.networkSettings = prefs.networkSettings;
    } catch {
      // Storage is unavailable (quota, private mode); in-memory defaults are still usable.
    }
  }

  /** Serialised write, so two sort changes cannot land out of order. */
  #persistPreferencesNow(): Promise<void> {
    if (!this.#persistPreferences) return Promise.resolve();
    const snapshot = {
      activeSourceId: this.#activeSourcePref,
      appLanguage: this.appLanguage,
      episodeSortOrders: { ...this.#sortOrders },
      readerSettings: { ...this.#readerSettings },
      networkSettings: this.networkSettings,
      sourceOrder: this.#sourceOrder,
      enabledSourceIds: this.#enabledSourceIds,
    } satisfies DesktopPrefs;
    this.#writeChain = this.#writeChain.then(
      () => this.#writePreferences(snapshot),
      () => this.#writePreferences(snapshot),
    );
    return this.#writeChain;
  }

  async #writePreferences(prefs: DesktopPrefs): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    try {
      await area.set({ [DESKTOP_PREFS_KEY]: prefs });
    } catch {
      // Preferences are a convenience; a failed write must not break navigation.
    }
  }
}

function clearTimeoutOf(timer: ReturnType<typeof setTimeout> | null): null {
  if (timer !== null) clearTimeout(timer);
  return null;
}

/**
 * Creates an independent desktop state store (one per shell surface, one per test).
 *
 * @param options.registry  Shared {@link SourceRegistry}; a private one is created by default.
 * @param options.persistPreferences  `false` keeps preferences in memory (tests).
 */
export function createDesktopStore(options: DesktopStoreOptions = {}): DesktopStore {
  return new DesktopStore(options);
}

/** Process-wide store used by the extension's single shell (`ReaderApp.svelte`). */
export const desktopState = createDesktopStore();
