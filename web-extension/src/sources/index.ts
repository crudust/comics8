/**
 * Public entry point for the source layer: the plugin host polyfill, loader, the (empty) built-in
 * registry and the registry of user-installed plugins.
 */

export {
  DEFAULT_CALL_TIMEOUT_MS,
  HOST_API_LEVEL,
  HostFetchResult,
  LoadedSource,
  PluginHost,
  createPluginHost,
  decodeEntities,
  extractSource,
  loadSource,
  type FetchSpec,
  type PluginHostOptions,
  type RawSource,
} from "./plugin-host";

export {
  DEFAULT_CONCURRENCY,
  DEFAULT_MAX_ATTEMPTS,
  DEFAULT_MAX_PASSES,
  DEFAULT_MAX_REQUESTS,
  RETRYABLE_STATUS,
  ReplayEngine,
  requestKey,
  type ReplayFetchExec,
  type ReplayOptions,
  type ReplayRequest,
  type ReplayResponse,
} from "./replay";

export { HostHtmlDoc, HostHtmlEl, parseHtmlDocument, resolveAbsUrl } from "./dom";

export {
  INSTALLED_SOURCES_KEY,
  SourceRegistry,
  type SourceRegistryOptions,
  type SourceSummary,
} from "./registry";

export { BUILTIN_SOURCES } from "./builtin";

export type {
  ArtistChoice,
  BuiltinSource,
  EpisodeItem,
  EpisodeResult,
  ListingResult,
  NotificationMode,
  PageInfo,
  ProgressDisplay,
  SearchQuery,
  SearchSuggestion,
  SourceCatalog,
  SourceMeta,
  ToonItem,
} from "./types";
