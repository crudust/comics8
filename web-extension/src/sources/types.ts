/**
 * Data models for Comics8 source plugins, mirroring `docs/PLUGIN_GUIDE.md` §3.
 *
 * These types are what a plugin returns through the host; they are intentionally structural (plain
 * objects) so plugin output can be validated without classes crossing the sandbox boundary.
 */

export type ArtistChoice = {
  slug: string;
  displayName: string;
};

/** §3.1 `ToonItem` — a comic series card. */
export type ToonItem = {
  id: string;
  title: string;
  thumbUrl: string;
  href: string;
  genre?: string | null;
  updatedAt?: string | null;
  ranking?: string | null;
  isNew?: boolean;
  entryEpisodeId?: string | null;
  artistChoices?: ArtistChoice[];
};

/** §3.2 `EpisodeItem` — a chapter. */
export type EpisodeItem = {
  wrId: string;
  title: string;
  href: string;
  date?: string | null;
  thumbUrl?: string | null;
  artistChoices?: ArtistChoice[];
};

/**
 * Fields a plugin may use to state the real number of episodes on a page.
 *
 * `docs/PLUGIN_GUIDE.md` only documents `currentPage` / `lastPage`, so a source that knows the
 * exact count declares it as an extra field. The host keeps every spelling instead of dropping it,
 * because a reported total is the only total that does not need a page sweep to exist.
 */
export type ReportedTotalFields = {
  total?: number | string;
  totalItems?: number | string;
  itemCount?: number | string;
  totalEpisodes?: number | string;
};

/** §3.3 `PageInfo` — 1-indexed pagination state (plus any total the plugin reported). */
export type PageInfo = ReportedTotalFields & {
  currentPage: number;
  lastPage: number;
};

export type ListingResult = {
  items: ToonItem[];
  pageInfo: PageInfo;
};

export type EpisodeResult = ReportedTotalFields & {
  items: EpisodeItem[];
  pageInfo: PageInfo;
};

export type SourceCatalog = {
  id: string;
  label: string;
  paginated: boolean;
};

export type NotificationMode = "NONE" | "LATEST_INTERSECTION" | "PER_FAVORITE";
export type ProgressDisplay = "LAST_READ_ORDER" | "READ_COUNT";

/** §2 `source` configuration, normalised with the documented defaults applied. */
export type SourceMeta = {
  id: string;
  displayName: string;
  origin: string;
  apiLevel: number;
  catalogs: SourceCatalog[];
  searchPlaceholder: string;
  userAgent: string | null;
  referer: string;
  extraHeaders: Record<string, string>;
  episodePageSize: number;
  notificationMode: NotificationMode;
  progressDisplay: ProgressDisplay;
  defaultLanguage: string | null;
  emptyListingOk: boolean;
  emptyEpisodesOk: boolean;
};

export type SearchQuery = {
  text: string;
  language?: string | null;
  type?: string | null;
};

export type SearchSuggestion = {
  ns: string;
  tag: string;
  count: number;
};

/** One bundled plugin shipped inside the extension. */
export type BuiltinSource = {
  id: string;
  displayName: string;
  /** Raw plugin JavaScript; loaded on demand. */
  script: string;
};
