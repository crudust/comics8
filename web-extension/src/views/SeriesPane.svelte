<script lang="ts">
  /**
   * Series surface, ported 1:1 from `desktop/src/main/kotlin/ui/series/SeriesPane.kt`.
   *
   * Mapping notes (deliberate, not accidental):
   * - The pane renders one `DesktopStore` (`src/state/desktop-state.svelte.ts`), the web twin of
   *   `DesktopUiState`; actions go through the store methods ported from `DesktopViewModel`,
   *   exactly like the Kotlin pane receives `state` + `viewModel`. It defaults to the process-wide
   *   `desktopState` (the shell's single instance); the `store` prop only exists so a test or a
   *   nested surface can render the pane against its own store. It is not named `state` because
   *   Svelte would then read `$state(...)` as a store subscription of that local binding.
   * - `store.series == null` → `ErrorPane(strings.errorNoSeriesInfo, strings.navBackToList)`
   *   ("작품 정보가 없습니다." / "목록으로").
   * - `EpisodeCard` mirrors `SeriesPane.kt` lines 199-349: the same three visual states (highlighted
   *   wins over read, like the Kotlin `when` picks `primaryContainer` before `surfaceVariant`),
   *   72x54 crop thumbnail, 2-line title, `labelSmall` date and the bold read badge with its date.
   * - Read state is `episode.isRead` / `episode.readAt` — the projection
   *   `RepositoryTransforms.enrichEpisodePage` writes on the desktop. A plugin page that was not
   *   enriched yet is answered from the `historyStore` read set instead, so the list never claims a
   *   chapter is unread after it was opened.
   * - Download state mirrors `SeriesPane.kt` lines 118-132: the card prints `Check`
   *   (`Icons.Filled.DownloadDone`) when `episode.wrId in store.downloadedWrIds`, a spinner while
   *   `store.downloadProgress.currentTask?.episode?.wrId` is that chapter
   *   (`isDownloading`), and otherwise the `Download` button that calls
   *   `viewModel.startDownloadEpisode(ep)`. The whole button only exists when `store.writesDownloads`
   *   (`DesktopUiState.writesDownloads` → `ComicSource.writesDownloads`, i.e. a remote plugin source).
   *   A downloaded or in-flight card disables the button, exactly like the Kotlin
   *   `enabled = !isDownloaded && !isDownloading`.
   * - The artist button (`MoreVert`, only when a chapter offers >= 2 artists) opens the inline
   *   replacement for `ArtistPickDialog`, then re-opens the resolved parent series — the same
   *   sequence as `DesktopViewModel.openArtistPicker` + `pickArtist`.
   * - Pull-to-refresh has no pointer gesture here; the header's refresh button calls the same
   *   `store.refresh()` the desktop indicator triggers.
   */

  import { AlertCircle, Check, Download, MoreVertical, RefreshCw } from 'lucide-svelte';

  import { historyStore } from '../data/history-store.svelte';
  import {
    desktopState,
    resolveEpisodeTotal,
    workKey,
  } from '../state/desktop-state.svelte';
  import type { DesktopStore } from '../state/desktop-state.svelte';
  import type { DesktopEpisodeItem, DesktopToonItem } from '../state/desktop-state.svelte';
  import type { ArtistChoice } from '../sources/types';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
  }

  let { store = desktopState }: Props = $props();

  /** `LocalStrings.errorNoSeriesInfo` / `LocalStrings.navBackToList` (ko). */
  const ERROR_NO_SERIES = '작품 정보가 없습니다.';
  const NAV_BACK = '목록으로';
  /** `LocalStrings.badgeRead` — the badge the Kotlin card prints verbatim. */
  const BADGE_READ = '읽음';

  /** `strings.labelDownloaded` / `strings.actionDownloadThisEpisode` (ko). */
  const LABEL_DOWNLOADED = '다운로드됨';
  const ACTION_DOWNLOAD_EPISODE = '이 회차 다운로드';

  const series = $derived(store.series);
  const episodes = $derived(store.episodes);
  const sourceId = $derived(
    series?.sourceId ?? store.activeSourceId ?? 'local',
  );

  /**
   * `strings.episodeItemCount(episodes.size)`, from the most authoritative total available.
   *
   * 1. `totalEpisodesByWork` (`DesktopStore.totalEpisodesOf`): a total the plugin reported, else the
   *    counted sweep, else the page-shape estimate — the only number that spans every episode page.
   * 2. `catalogTotals[key].count`: a verified page sweep, written before the reactive map existed.
   * 3. The loaded page length: the only fact left when nothing was counted yet.
   */
  const total = $derived.by(() => {
    const current = series;
    if (!current) return 0;
    const key = workKey(sourceId, current.id);
    return resolveEpisodeTotal(
      store.totalEpisodesOf(key),
      store.catalogTotals[key]?.count,
      store.episodes.length,
    );
  });
  const totalLabel = $derived(`총 ${total}화`);
  const readProgressLabel = $derived.by(() => {
    const hist = store.seriesHistory;
    if (!hist) return null;
    const label = store.progressLabel(hist).trim();
    return label.length > 0 ? label : null;
  });
  /** Multi-page listings name the page they are showing next to the total. */
  const pageSummary = $derived(
    store.episodeLastPage > 1
      ? `${totalLabel} (${store.episodePage} / ${store.episodeLastPage} 페이지)`
      : null,
  );

  const canPage = $derived(!store.episodeLoading);
  const busy = $derived(
    store.refreshing || (store.episodeLoading && store.episodes.length > 0),
  );

  /** `store.artistPick`: the inline twin of `ArtistPickRequest(item, entryEpisodeId)`. */
  let artistPick = $state<{
    item: DesktopToonItem;
    episode: DesktopEpisodeItem;
    choices: ArtistChoice[];
  } | null>(null);

  // ---------------------------------------------------------------------------------------------
  // read state (`episode.isRead` / `episode.readAt`, `historyStore` fallback)
  // ---------------------------------------------------------------------------------------------

  function readRecordAt(episode: DesktopEpisodeItem): number | null {
    if (typeof episode.readAt === 'number' && episode.readAt > 0) return episode.readAt;
    const current = series;
    if (!current) return null;
    const record = historyStore.readEpisodes.find(
      (row) =>
        row.sourceId === sourceId &&
        row.toonId === current.id &&
        row.wrId === episode.wrId,
    );
    return record?.readAt ?? null;
  }

  function isRead(episode: DesktopEpisodeItem): boolean {
    if (episode.isRead === true) return true;
    const current = series;
    if (current && historyStore.getProgress(sourceId, current.id)?.lastWrId === episode.wrId) {
      return true;
    }
    return readRecordAt(episode) !== null;
  }

  /** `UpdateDates.formatEpoch(readAt)` — the desktop pattern is `yy.MM.dd`. */
  function formatEpoch(millis: number): string {
    const date = new Date(millis);
    const yy = `${date.getFullYear() % 100}`.padStart(2, '0');
    const mm = `${date.getMonth() + 1}`.padStart(2, '0');
    const dd = `${date.getDate()}`.padStart(2, '0');
    return `${yy}.${mm}.${dd}`;
  }

  // ---------------------------------------------------------------------------------------------
  // actions (DesktopViewModel equivalents)
  // ---------------------------------------------------------------------------------------------

  function openEpisode(episode: DesktopEpisodeItem): void {
    void store.openEpisode(episode);
  }

  /** `isDownloaded = ep.wrId in state.downloadedWrIds`. */
  function isDownloaded(episode: DesktopEpisodeItem): boolean {
    return store.downloadedWrIds.has(episode.wrId);
  }

  /** `isDownloading = state.downloadProgress.currentTask?.episode?.wrId == ep.wrId`. */
  function isDownloading(episode: DesktopEpisodeItem): boolean {
    return store.downloadProgress.currentTask?.episode?.wrId === episode.wrId;
  }

  /** `onDownload = { viewModel.startDownloadEpisode(ep) }` — null when the source cannot save. */
  function downloadEpisode(episode: DesktopEpisodeItem): void {
    store.startDownloadEpisode(episode);
  }

  function onCardKeydown(event: KeyboardEvent, episode: DesktopEpisodeItem): void {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    openEpisode(episode);
  }

  function retry(): void {
    void store.loadEpisodes();
  }

  function refresh(): void {
    void store.refresh();
  }

  function goToPage(page: number): void {
    if (!canPage) return;
    void store.goToEpisodePage(page);
  }

  /** `FilledTonalButton(onClick = { viewModel.togglePageJump(true) })` next to the arrows. */
  function openPageJump(): void {
    if (!canPage) return;
    store.togglePageJump(true);
  }

  /** `DesktopViewModel.openArtistPicker(item, entryEpisodeId)`. */
  function openArtistPicker(episode: DesktopEpisodeItem): void {
    const current = series;
    const choices = episode.artistChoices ?? [];
    if (!current || choices.length < 2) return;
    // The Kotlin call passes `series.copy(artistChoices = ep.artistChoices)` + `ep.wrId`.
    artistPick = {
      item: { ...current, artistChoices: choices },
      episode,
      choices,
    };
  }

  /** `DesktopViewModel.pickArtist(artist)`: resolve the parent, then open it as a series. */
  async function pickArtist(choice: ArtistChoice): Promise<void> {
    const request = artistPick;
    artistPick = null;
    if (!request) return;

    const source = await store.registry.get(sourceId).catch(() => null);
    if (!source || !('resolveParent' in source) || typeof source.resolveParent !== 'function') {
      return;
    }
    const parent = await source
      .resolveParent(request.item, choice, request.episode.wrId)
      .catch(() => null);
    if (parent) await store.openSeries(parent);
  }
</script>

{#if !series}
  <!-- `ErrorPane(strings.errorNoSeriesInfo, strings.navBackToList)` -->
  <section class="pane">
    <div class="state" role="alert">
      <span class="state-icon" aria-hidden="true"><AlertCircle size={36} /></span>
      <p class="state-title">{ERROR_NO_SERIES}</p>
      <button type="button" class="state-action" onclick={() => void store.closeSeries()}>
        {NAV_BACK}
      </button>
    </div>
  </section>
{:else}
  <section class="pane">
    <header class="header">
      <div class="header-text">
        <h1 class="title" title={series.title}>{series.title}</h1>
        <span class="total-badge">{totalLabel}</span>
        {#if readProgressLabel}
          <span class="read-progress-badge">{readProgressLabel}</span>
        {/if}
        {#if pageSummary}
          <span class="page-summary">{pageSummary}</span>
        {/if}
      </div>
      <button
        type="button"
        class="refresh"
        title="새로고침"
        aria-label="새로고침"
        disabled={canPage === false}
        onclick={refresh}
      >
        <RefreshCw size={14} aria-hidden="true" />
      </button>
    </header>

    {#if busy}
      <div class="refresh-strip" role="status">
        <span class="spinner small" aria-hidden="true"></span>
        <span>불러오는 중...</span>
      </div>
    {/if}

    <div class="body">
      {#if store.episodeLoading && episodes.length === 0}
        <!-- `LoadingPane` -->
        <div class="state loading" role="status">
          <span class="spinner" aria-hidden="true"></span>
          <p class="state-hint">회차 목록을 불러오는 중...</p>
        </div>
      {:else if store.episodeError}
        <!-- `ErrorPane(message, onRetry = { loadEpisodes(series, episodePage) })` -->
        <div class="state" role="alert">
          <span class="state-icon" aria-hidden="true"><AlertCircle size={36} /></span>
          <p class="state-title">{store.episodeError}</p>
          <button type="button" class="state-action" onclick={retry}>
            <RefreshCw size={13} aria-hidden="true" />
            다시 시도
          </button>
        </div>
      {:else}
        <!-- `LazyColumn(items = store.episodes.distinctBy { it.wrId })` -->
        <ul class="episode-list">
          {#each episodes as episode (episode.wrId)}
            {@const read = isRead(episode)}
            {@const readAt = readRecordAt(episode)}
            {@const highlighted = episode.wrId === store.highlightedEpisodeId}
            <li>
              <div
                class="episode-card"
                class:read
                class:highlighted
                role="button"
                tabindex="0"
                aria-label={episode.title}
                title={episode.title}
                onclick={() => openEpisode(episode)}
                onkeydown={(event) => onCardKeydown(event, episode)}
              >
                <span class="thumb">
                  {#if episode.thumbUrl}
                    <img
                      src={episode.thumbUrl}
                      alt=""
                      loading="lazy"
                      decoding="async"
                      class="thumb-image"
                    />
                  {/if}
                </span>

                <span class="episode-text">
                  <span class="episode-title">{episode.title}</span>
                  {#if episode.date}
                    <span class="episode-date">{episode.date}</span>
                  {/if}
                </span>

                {#if read}
                  <span class="read-badge-group">
                    <span class="read-badge">{BADGE_READ}</span>
                    {#if readAt !== null}
                      <span class="read-date">{formatEpoch(readAt)}</span>
                    {/if}
                  </span>
                {/if}

                {#if (episode.artistChoices?.length ?? 0) >= 2}
                  <button
                    type="button"
                    class="artist-button"
                    title="다른 작가"
                    aria-label="다른 작가"
                    onclick={(event) => {
                      event.stopPropagation();
                      openArtistPicker(episode);
                    }}
                  >
                    <MoreVertical size={18} aria-hidden="true" />
                  </button>
                {/if}

                {#if store.writesDownloads}
                  {@const downloaded = isDownloaded(episode)}
                  {@const downloading = isDownloading(episode)}
                  <!-- `IconButton(size = 36.dp, enabled = !isDownloaded && !isDownloading)` -->
                  <button
                    type="button"
                    class="download-button"
                    class:downloaded
                    class:downloading
                    title={downloaded
                      ? LABEL_DOWNLOADED
                      : downloading
                        ? '다운로드 중...'
                        : ACTION_DOWNLOAD_EPISODE}
                    aria-label={downloaded
                      ? LABEL_DOWNLOADED
                      : downloading
                        ? '다운로드 중...'
                        : ACTION_DOWNLOAD_EPISODE}
                    aria-busy={downloading}
                    disabled={downloaded || downloading}
                    onclick={(event) => {
                      event.stopPropagation();
                      downloadEpisode(episode);
                    }}
                  >
                    {#if downloading}
                      <span class="spinner tiny" aria-hidden="true"></span>
                    {:else if downloaded}
                      <Check size={20} aria-hidden="true" />
                    {:else}
                      <Download size={20} aria-hidden="true" />
                    {/if}
                  </button>
                {/if}
              </div>
            </li>
          {/each}
        </ul>
      {/if}
    </div>

    {#if store.episodeLastPage > 1}
      <!-- `if (store.episodeLastPage > 1)` pagination row -->
      <nav class="pagination" aria-label="회차 페이지 이동">
        <button
          type="button"
          class="page-button"
          title="이전"
          aria-label="이전"
          disabled={!canPage || store.episodePage <= 1}
          onclick={() => goToPage(store.episodePage - 1)}
        >
          <span aria-hidden="true">‹</span>
        </button>

        <button
          type="button"
          class="page-label page-jump"
          aria-live="polite"
          aria-haspopup="dialog"
          aria-label={`회차 페이지 이동 (${store.episodePage} / ${store.episodeLastPage})`}
          title="페이지 이동"
          disabled={!canPage}
          onclick={openPageJump}
        >
          {store.episodePage} / {store.episodeLastPage}
        </button>

        <button
          type="button"
          class="page-button"
          title="다음"
          aria-label="다음"
          disabled={!canPage || store.episodePage >= store.episodeLastPage}
          onclick={() => goToPage(store.episodePage + 1)}
        >
          <span aria-hidden="true">›</span>
        </button>
      </nav>
    {/if}
  </section>
{/if}

{#if artistPick}
  <!-- `ArtistPickDialog(artists = artistPick.item.artistChoices)` -->
  <div
    class="dialog-backdrop"
    role="presentation"
    onclick={() => (artistPick = null)}
  ></div>
  <div class="dialog" role="dialog" aria-modal="true" aria-label="다른 작가">
    <p class="dialog-title">작가 선택</p>
    <ul class="artist-options">
      {#each artistPick.choices as choice (choice.slug)}
        <button type="button" class="artist-option" onclick={() => void pickArtist(choice)}>
          {choice.displayName || choice.slug}
        </button>
      {/each}
    </ul>
    <button type="button" class="dialog-cancel" onclick={() => (artistPick = null)}>닫기</button>
  </div>
{/if}

<style>
  /* --- frame: pure black surface, Zinc borders --------------------------------------------- */

  .pane {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
    background: #000000;
    color: #f4f4f5;
  }

  .header {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 10px;
    padding: 10px 16px;
    border-bottom: 1px solid rgba(63, 63, 70, 0.55);
    background: #000000;
  }

  .header-text {
    display: flex;
    flex: 1 1 auto;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 10px;
    min-width: 0;
  }

  .title {
    min-width: 0;
    margin: 0;
    font-size: 15px;
    font-weight: 700;
    color: #f4f4f5;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* `strings.episodeItemCount(total)` chip: Zinc 800 fill, near-white bold label, 6dp rounded. */
  .total-badge {
    flex: 0 0 auto;
    padding: 3px 8px;
    border-radius: 6px;
    background: #27272a;
    color: #fafafa;
    font-size: 12px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    line-height: 1.25;
    white-space: nowrap;
  }

  .read-progress-badge {
    flex: 0 0 auto;
    padding: 3px 8px;
    border-radius: 6px;
    background: rgba(59, 130, 246, 0.2);
    border: 1px solid rgba(59, 130, 246, 0.4);
    color: #60a5fa;
    font-size: 12px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    line-height: 1.25;
    white-space: nowrap;
  }

  /* "총 N화 (page / lastPage 페이지)" — only painted for a paginated listing. */
  .page-summary {
    flex: 0 0 auto;
    color: #a1a1aa;
    font-size: 11.5px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }

  .refresh {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    padding: 0;
    border: 1px solid rgba(63, 63, 70, 0.7);
    border-radius: 8px;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
  }

  .refresh:hover:not(:disabled) {
    border-color: rgba(113, 113, 122, 0.9);
    color: #e4e4e7;
  }

  .refresh:disabled {
    opacity: 0.4;
    cursor: default;
  }

  .body {
    flex: 1 1 auto;
    min-height: 0;
    overflow-y: auto;
    padding: 8px;
  }

  /* --- loading / error panes -------------------------------------------------------------- */

  .state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 200px;
    height: 100%;
    padding: 32px 16px;
    text-align: center;
  }

  .state-icon {
    display: inline-flex;
    color: #52525b;
  }

  .state-title {
    margin: 0;
    font-size: 13.5px;
    font-weight: 700;
    color: #f4f4f5;
    overflow-wrap: anywhere;
  }

  .state-hint {
    margin: 0;
    font-size: 12px;
    color: rgba(255, 255, 255, 0.65);
    overflow-wrap: anywhere;
  }

  .state-action {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    margin-top: 2px;
    padding: 6px 12px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 7px;
    background: rgba(24, 24, 27, 0.9);
    color: #e4e4e7;
    font: 600 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .state-action:hover {
    border-color: rgba(113, 113, 122, 0.9);
  }

  /* --- episode list ----------------------------------------------------------------------- */

  .episode-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  /* `EpisodeCard` surface: 12dp rounded, 1dp outlineVariant@0.25 border, 8dp padding. */
  .episode-card {
    display: flex;
    align-items: center;
    gap: 0;
    padding: 8px;
    border: 1px solid rgba(63, 63, 70, 0.45);
    border-radius: 12px;
    /* unread: surfaceVariant @ 0.6 */
    background: rgba(24, 24, 27, 0.6);
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s, opacity 0.15s;
  }

  /* highlighted: primaryContainer @ 0.55 — wins over read, like the Kotlin `when`. */
  .episode-card.highlighted {
    opacity: 1;
    border-color: rgba(96, 165, 250, 0.55);
    background: rgba(39, 39, 42, 0.55);
    box-shadow: inset 0 0 0 1px rgba(96, 165, 250, 0.28);
  }

  /* read: surfaceVariant @ 0.35 → de-emphasised row. */
  .episode-card.read {
    opacity: 0.35;
  }

  .episode-card.read:hover,
  .episode-card.read:focus-visible {
    opacity: 0.55;
  }

  .episode-card:hover {
    border-color: rgba(113, 113, 122, 0.6);
  }

  .episode-card:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  /* 72dp x 54dp, 8dp rounded, ContentScale.Crop. */
  .thumb {
    position: relative;
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 72px;
    height: 54px;
    overflow: hidden;
    border: 0.5px solid rgba(63, 63, 70, 0.55);
    border-radius: 8px;
    background: #27272a;
  }

  .thumb-image {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .episode-text {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    padding: 0 10px;
  }

  .episode-title {
    display: -webkit-box;
    overflow: hidden;
    font-size: 13px;
    font-weight: 500; /* unread: FontWeight.Medium */
    line-height: 1.35;
    color: #f4f4f5;
    overflow-wrap: anywhere;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
  }

  /* read: FontWeight.Normal + onSurfaceVariant @ 0.65 */
  .episode-card.read .episode-title {
    font-weight: 400;
    color: rgba(255, 255, 255, 0.65);
  }

  /* labelSmall under the title (onSurfaceVariant @ 0.8). */
  .episode-date {
    font-size: 11px;
    line-height: 1.3;
    color: rgba(255, 255, 255, 0.64);
    font-variant-numeric: tabular-nums;
  }

  .read-badge-group {
    display: flex;
    flex: 0 0 auto;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding-right: 6px;
  }

  /* secondaryContainer @ 0.85, labelSmall, bold, 6dp rounded. */
  .read-badge {
    padding: 2px 6px;
    border-radius: 6px;
    background: rgba(96, 165, 250, 0.85);
    color: #0b1220;
    font-size: 11px;
    font-weight: 700;
    line-height: 1.25;
    white-space: nowrap;
  }

  .read-date {
    font-size: 10px;
    line-height: 1.2;
    color: rgba(255, 255, 255, 0.75);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }

  .artist-button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
  }

  .artist-button:hover,
  .artist-button:focus-visible {
    background: rgba(63, 63, 70, 0.6);
    color: #f4f4f5;
  }

  /* `IconButton(size = 36.dp)` at the end of the card (SeriesPane.kt lines 328-348). */
  .download-button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .download-button:hover:not(:disabled) {
    background: rgba(63, 63, 70, 0.6);
    color: #f4f4f5;
  }

  .download-button:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  /* `Icons.Filled.DownloadDone` tinted `primary`. */
  .download-button.downloaded {
    color: #fafafa;
  }

  .download-button:disabled {
    cursor: default;
  }

  /* The card's dimming (`.read`) must not fade the download state away. */
  .episode-card.read .download-button {
    opacity: 1;
  }

  .spinner.tiny {
    width: 16px;
    height: 16px;
    border-width: 2px;
    border-top-color: #fafafa;
  }

  /* --- pagination ------------------------------------------------------------------------- */

  /* `Row(horizontalArrangement = Center).background(surfaceVariant.copy(alpha = 0.3f)).padding(vertical = 8dp)`. */
  .pagination {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 8px;
    margin: 0 8px 8px;
    padding: 8px 0;
    border-radius: 8px;
    background: rgba(39, 39, 42, 0.4);
  }

  .page-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 28px;
    padding: 0;
    border: 1px solid rgba(63, 63, 70, 0.7);
    border-radius: 8px;
    background: transparent;
    color: #e4e4e7;
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
  }

  .page-button:hover:not(:disabled) {
    border-color: rgba(113, 113, 122, 0.9);
    background: rgba(39, 39, 42, 0.8);
  }

  .page-button:disabled {
    opacity: 0.4;
    cursor: default;
  }

  /* FilledTonalButton, 8dp rounded, labelMedium bold; now the `togglePageJump(true)` trigger. */
  .page-label {
    padding: 6px 14px;
    border: 0;
    border-radius: 8px;
    background: rgba(39, 39, 42, 0.95);
    color: #e4e4e7;
    font: 700 12px/1.2 var(--font-sans, system-ui, sans-serif);
    font-variant-numeric: tabular-nums;
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .page-label:hover:not(:disabled) {
    background: rgba(63, 63, 70, 0.95);
    color: #fafafa;
  }

  .page-label:disabled {
    opacity: 0.4;
    cursor: default;
  }

  /* --- busy strip / spinner --------------------------------------------------------------- */

  .refresh-strip {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 7px;
    padding: 4px;
    background: rgba(9, 9, 11, 0.9);
    color: #bfdbfe;
    font-size: 11.5px;
    font-weight: 600;
  }

  .spinner {
    width: 20px;
    height: 20px;
    border: 2px solid rgba(255, 255, 255, 0.16);
    border-top-color: #60a5fa;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  .spinner.small {
    width: 12px;
    height: 12px;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  /* --- artist picker (ArtistPickDialog) --------------------------------------------------- */

  .dialog-backdrop {
    position: fixed;
    inset: 0;
    z-index: 40;
    background: rgba(0, 0, 0, 0.6);
  }

  .dialog {
    position: fixed;
    top: 50%;
    left: 50%;
    z-index: 41;
    display: flex;
    flex-direction: column;
    gap: 10px;
    width: min(320px, calc(100% - 32px));
    padding: 16px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 12px;
    background: #09090b;
    color: #f4f4f5;
    transform: translate(-50%, -50%);
  }

  .dialog-title {
    margin: 0;
    font-size: 13px;
    font-weight: 700;
  }

  .artist-options {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .artist-option {
    padding: 9px 12px;
    border: 1px solid rgba(63, 63, 70, 0.6);
    border-radius: 8px;
    background: rgba(24, 24, 27, 0.9);
    color: #e4e4e7;
    font: 600 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    text-align: left;
    cursor: pointer;
  }

  .artist-option:hover {
    border-color: rgba(113, 113, 122, 0.9);
    background: rgba(39, 39, 42, 0.9);
  }

  .dialog-cancel {
    align-self: flex-end;
    padding: 6px 12px;
    border: 0;
    border-radius: 7px;
    background: transparent;
    color: #a1a1aa;
    font: 600 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .dialog-cancel:hover {
    color: #f4f4f5;
  }

  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation: none;
    }

    .episode-card {
      transition: none;
    }
  }
</style>
