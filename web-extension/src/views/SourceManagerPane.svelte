<script lang="ts">
  /**
   * Source manager, ported 1:1 from `desktop/src/main/kotlin/ui/source/SourceManagerPane.kt`.
   *
   * Mapping notes (deliberate, not accidental):
   * - The pane renders one `DesktopStore` (`src/state/desktop-state.svelte.ts`), the web twin of
   *   `DesktopUiState`; every action goes through the store methods ported from `DesktopViewModel`
   *   (`closeSourceManager`, `toggleSourceEnabled`, `reorderSources`, `uninstallSource`,
   *   `importJsFile`). It defaults to the process-wide `desktopState`; the `store` prop only exists
   *   so a test or a nested surface can render the pane against its own store. It is not named
   *   `state` because Svelte would read `$state(...)` as a store subscription of that local binding.
   * - The Kotlin `Scaffold` + `TopAppBar` becomes the header row: back arrow
   *   (`strings.actionGoBack` → `closeSourceManager`), the `navSourceManager` title, and the
   *   `actionAddSource` button. On the desktop that button opens `SourceAddOptionsDialog` (local
   *   folder / WebDAV / JS); the web shell has no folder picker and no WebDAV *source*
   *   backend, so the one source type it can actually add — a `.js` plugin — is the header action
   *   itself (`pickJsFile()` → `importJsFile`).
   * - Both Kotlin sections are kept: `sectionStorageSources` (the fixed local row, then the
   *   reorderable WebDAV rows) and `sectionOnlineSources` (web / JS sources). `local` is pinned
   *   first and has no reorder or trash action, exactly like the Kotlin `localSource` row.
   * - A row carries the parts of the Kotlin `SourceItemCard` that this screen is about: the leading
   *   `Folder` / `Cloud` / `FileCode` glyph, the title (`ComicSource.displayTitle`), the subtitle
   *   (`SourceType.displayLabel`, the WebDAV address where the summary knows it), and the
   *   trailing controls. The web shell has no detail pane, so instead of the Kotlin
   *   `ArrowForwardIos` "open settings" chevron the row ends with the controls the store exposes:
   *   the desktop `SourcePrefs.setEnabled` switch, the reorder arrows that replace the Compose
   *   drag handle (`reorderStorageSources` / `reorderOnlineSources`), and the uninstall trash button
   *   (`IconButton(Outlined.Delete)` of `SourceDetailSettingsPane`) for every installed JS script.
   *   The extension ships no built-in plugin, so every JS row is user-installed and removable.
   * - `LazyColumn(spacedBy(12.dp))` → one scrolling column with the same 12px gap and the same
   *   16dp/8dp/24dp content padding; cards reuse the Kotlin `surfaceVariant` + `RoundedCornerShape
   *   (12.dp)` treatment on the shell's pure black canvas.
   * - The Kotlin `EmptySectionCard` shows when no online source is registered; its action is the
   *   same JS import the header button triggers (`openAddSourceSheet` on the desktop).
   */

  import {
    ArrowLeft,
    ChevronDown,
    ChevronUp,
    Cloud,
    FileCode,
    Folder,
    Plus,
    Trash2,
  } from 'lucide-svelte';

  import { desktopState } from '../state/desktop-state.svelte';
  import {
    isStorageSource,
    resolveSourceKind,
    sourceDisplayTitle,
    sourceKindLabel,
    type DesktopStore,
  } from '../state/desktop-state.svelte';
  import type { SourceSummary } from '../sources/registry';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
  }

  let { store = desktopState }: Props = $props();

  /** `LocalStrings.navSourceManager` / `actionGoBack` / `actionAddSource` (ko). */
  const TITLE = '소스 관리';
  const ACTION_GO_BACK = '뒤로가기';
  const ACTION_ADD_SOURCE = '+ .js 플러그인 추가';
  /** `LocalStrings.sectionStorageSources` / `sectionOnlineSources` (ko). */
  const SECTION_STORAGE = '로컬 / WebDAV';
  const SECTION_ONLINE = '웹 / JS 스크립트';
  /** `LocalStrings.emptyOnlineSources` / `actionAddJsSource` (ko). */
  const EMPTY_ONLINE = '등록된 온라인/확장 소스가 없습니다.';
  const ACTION_ADD_JS_SOURCE = 'JS 확장 스크립트 추가';
  /** `LocalStrings.labelFixed` / `actionReorder` / `actionDelete` (ko). */
  const LABEL_FIXED = '고정됨';
  const ACTION_REORDER = '순서 변경';
  const ACTION_DELETE = '삭제';
  /** `SourceDetailSettingsPane`'s confirm dialog (`strings.confirmDeleteSource(name)`). */
  const confirmDelete = (name: string): string => `'${name}' 소스를 목록에서 삭제하시겠습니까?`;

  let fileInput: HTMLInputElement | undefined = $state();

  const sources = $derived(store.installedSources);
  /** `sources.filter { it.resolveSourceType().isStorage }`. */
  const storageSources = $derived(sources.filter((source) => isStorageSource(source.id)));
  /** The pinned first row (`it.id == WorkId.LOCAL_SOURCE || type == LOCAL`). */
  const localSource = $derived(
    storageSources.find((source) => resolveSourceKind(source.id) === 'LOCAL') ?? null,
  );
  /** `networkStorageSources` — reorderable, in stored order. */
  const networkStorageSources = $derived(
    storageSources.filter((source) => source.id !== localSource?.id),
  );
  /** `onlineSources` — reorderable, in stored order. */
  const onlineSources = $derived(sources.filter((source) => !isStorageSource(source.id)));

  /** `SourceSummary.origin || source.id`: what the row names under the display title. */
  function originOf(source: SourceSummary): string {
    return source.origin.trim() !== '' ? source.origin : source.id;
  }

  /** `Icon` of the Kotlin card: Folder / Cloud / Description→FileCode. */
  function sourceIcon(source: SourceSummary) {
    switch (resolveSourceKind(source.id)) {
      case 'LOCAL':
        return Folder;
      case 'WEBDAV':
        return Cloud;
      default:
        return FileCode;
    }
  }

  /** `subtitleOf`: the source type label, with the WebDAV address where it is known. */
  function subtitleOf(source: SourceSummary): string {
    const origin = originOf(source);
    switch (resolveSourceKind(source.id)) {
      case 'LOCAL':
        return origin;
      case 'WEBDAV':
        return `WebDAV: ${origin}`;
      default:
        return sourceKindLabel(resolveSourceKind(source.id));
    }
  }

  /** The row belongs to one of the two reorderable lists (`local` is pinned first). */
  function canReorder(source: SourceSummary): boolean {
    return source.id !== localSource?.id;
  }

  function canMoveUp(list: readonly SourceSummary[], source: SourceSummary): boolean {
    return list.findIndex((row) => row.id === source.id) > 0;
  }

  function canMoveDown(list: readonly SourceSummary[], source: SourceSummary): boolean {
    const index = list.findIndex((row) => row.id === source.id);
    return index >= 0 && index < list.length - 1;
  }

  /**
   * The trash action: every installed JS plugin can be removed (`SourceRegistry.uninstall`).
   *
   * `builtin` only ever matters when a caller registers one explicitly (tests); storage sources and
   * inlined plugins are managed by storage settings instead.
   */
  function canUninstall(source: SourceSummary): boolean {
    return !source.builtin && resolveSourceKind(source.id) === 'JS';
  }

  function reorder(list: readonly SourceSummary[], source: SourceSummary, offset: number): void {
    if (!canReorder(source)) return;
    const from = list.findIndex((row) => row.id === source.id);
    if (from < 0) return;
    const to = from + offset;
    if (to < 0 || to >= list.length) return;
    store.reorderSources(source.id, offset);
  }

  function toggle(source: SourceSummary): void {
    void store.toggleSourceEnabled(source.id);
  }

  async function uninstall(source: SourceSummary): Promise<void> {
    if (!canUninstall(source)) return;
    if (!confirm(confirmDelete(sourceDisplayTitle(source)))) return;
    await store.uninstallSource(source.id);
  }

  /** `pickJsFile()`: the hidden `<input type="file" accept=".js">` of the header action. */
  function pickJsFile(): void {
    store.sourceError = null;
    fileInput?.click();
  }

  async function onFileChange(event: Event): Promise<void> {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    await store.importJsFile(file);
  }
</script>

{#snippet sourceRow(source: SourceSummary, list: SourceSummary[] | null, deletable: boolean)}
  {@const Icon = sourceIcon(source)}
  {@const title = sourceDisplayTitle(source)}
  <div class="source-card" data-source-id={source.id}>
    <span class="glyph" aria-hidden="true"><Icon size={20} /></span>

    <div class="text">
      <p class="name" title={title}>{title}</p>
      <p class="origin" title={subtitleOf(source)}>{subtitleOf(source)}</p>
    </div>

    <div class="controls">
      {#if list === null}
        <!-- `local` is always enabled and fixed (`SourcePrefs.setEnabled` refuses it). -->
        <span class="fixed-label" title={LABEL_FIXED}>{LABEL_FIXED}</span>
        <label class="switch" title="사용">
          <input
            type="checkbox"
            checked={store.isSourceEnabled(source.id)}
            disabled
            aria-label={`${title} 사용`}
          />
          <span class="track" aria-hidden="true"><span class="thumb"></span></span>
        </label>
      {:else}
        <!-- `SourcePrefs.setEnabled` toggle (`toggleSourceEnabled`) -->
        <label class="switch" title="사용">
          <input
            type="checkbox"
            checked={store.isSourceEnabled(source.id)}
            aria-label={`${title} 사용`}
            onchange={() => toggle(source)}
          />
          <span class="track" aria-hidden="true"><span class="thumb"></span></span>
        </label>

        <!-- Compose drag handle → the reorder arrows of `reorderSources` -->
        <div class="reorder" role="group" aria-label={ACTION_REORDER}>
          <button
            type="button"
            class="icon-button small"
            title={`${ACTION_REORDER} (위로)`}
            aria-label={`${title} 위로 이동`}
            disabled={!canMoveUp(list, source)}
            onclick={() => reorder(list, source, -1)}
          >
            <ChevronUp size={16} aria-hidden="true" />
          </button>
          <button
            type="button"
            class="icon-button small"
            title={`${ACTION_REORDER} (아래로)`}
            aria-label={`${title} 아래로 이동`}
            disabled={!canMoveDown(list, source)}
            onclick={() => reorder(list, source, 1)}
          >
            <ChevronDown size={16} aria-hidden="true" />
          </button>
        </div>
      {/if}

      {#if deletable}
        <!-- `IconButton(Outlined.Delete)` → `uninstallSource(source.id)` -->
        <button
          type="button"
          class="icon-button danger"
          title={ACTION_DELETE}
          aria-label={`${title} 삭제`}
          onclick={() => void uninstall(source)}
        >
          <Trash2 size={16} aria-hidden="true" />
        </button>
      {/if}
    </div>
  </div>
{/snippet}

<section class="pane" aria-label={TITLE}>
  <header class="top-bar">
    <button
      type="button"
      class="icon-button"
      title={ACTION_GO_BACK}
      aria-label={ACTION_GO_BACK}
      onclick={() => store.closeSourceManager()}
    >
      <ArrowLeft size={20} aria-hidden="true" />
    </button>

    <h1 class="title" title={TITLE}>{TITLE}</h1>

    <!-- `Button(onClick = { showAddOptions = true })` → `pickJsFile()?.let(importJsFile)` -->
    <button type="button" class="add-button" onclick={pickJsFile}>
      <Plus size={16} aria-hidden="true" />
      <span>{ACTION_ADD_SOURCE}</span>
    </button>

    <input
      bind:this={fileInput}
      type="file"
      accept=".js"
      class="file-input"
      aria-label="JS 플러그인 파일"
      onchange={onFileChange}
    />
  </header>

  {#if store.sourceError}
    <!-- `state.sourceError` banner (`DesktopUiState.sourceError`). -->
    <div class="error-banner" role="alert">{store.sourceError}</div>
  {/if}

  <div class="content">
    <!-- ========================================== -->
    <!-- 분류 1: 로컬 / WebDAV                      -->
    <!-- ========================================== -->
    <div class="section-header">
      <Folder size={18} aria-hidden="true" />
      <span>{SECTION_STORAGE}</span>
    </div>

    {#if localSource}
      <!-- 최상단 고정 로컬 저장소: no reorder, no trash (`localSource` branch of the Kotlin pane) -->
      {@render sourceRow(localSource, null, false)}
    {/if}

    {#each networkStorageSources as source (source.id)}
      {@render sourceRow(source, networkStorageSources, false)}
    {/each}

    <!-- ========================================== -->
    <!-- 분류 2: 웹 / JS 스크립트                   -->
    <!-- ========================================== -->
    <div class="section-header spaced">
      <FileCode size={18} aria-hidden="true" />
      <span>{SECTION_ONLINE}</span>
    </div>

    {#if onlineSources.length === 0}
      <!-- `EmptySectionCard(text = emptyOnlineSources, actionText = actionAddJsSource)` -->
      <div class="empty-card">
        <p>{EMPTY_ONLINE}</p>
        <button type="button" class="empty-action" onclick={pickJsFile}>
          <Plus size={14} aria-hidden="true" />
          <span>{ACTION_ADD_JS_SOURCE}</span>
        </button>
      </div>
    {:else}
      {#each onlineSources as source (source.id)}
        {@render sourceRow(source, onlineSources, canUninstall(source))}
      {/each}
    {/if}
  </div>
</section>

<style>
  /* --- frame: pure black surface, Zinc borders (the shell's MonochromeTheme) ----------------- */

  .pane {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
    background: #000000;
    color: #f4f4f5;
  }

  /* `TopAppBar(containerColor = background)`: 8dp vertical padding, 16dp horizontal. */
  .top-bar {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    background: #000000;
  }

  /* `Text(style = titleMedium, fontWeight = Bold)` */
  .title {
    flex: 1 1 auto;
    min-width: 0;
    margin: 0;
    padding: 0 4px;
    font-size: 17px;
    font-weight: 700;
    letter-spacing: -0.01em;
    line-height: 1.3;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* `Button(primary, RoundedCornerShape(10.dp), contentPadding = 12dp/6dp)` */
  .add-button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 4px;
    padding: 6px 12px;
    border: none;
    border-radius: 10px;
    background: #ffffff;
    color: #000000;
    font: 700 11.5px/1.2 var(--font-sans, system-ui, sans-serif);
    white-space: nowrap;
    cursor: pointer;
    transition: background 0.15s;
  }

  .add-button:hover {
    background: #e4e4e7;
  }

  /* `pickJsFile()` is triggered by the two buttons; the picker itself stays hidden. */
  .file-input {
    display: none;
  }

  /* `IconButton` — 34px hit target with a zinc hover wash. */
  .icon-button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    padding: 0;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .icon-button:hover:not(:disabled) {
    background: rgba(39, 39, 42, 0.85);
    color: #fafafa;
  }

  .icon-button:disabled {
    opacity: 0.35;
    cursor: default;
  }

  .icon-button.small {
    width: 26px;
    height: 26px;
    border-radius: 6px;
  }

  /* `colorScheme.error` for destructive actions. */
  .icon-button.danger {
    color: #f87171;
  }

  .icon-button.danger:hover:not(:disabled) {
    background: rgba(127, 29, 29, 0.55);
    color: #fecaca;
  }

  .error-banner {
    flex: 0 0 auto;
    margin: 0 16px;
    padding: 8px 12px;
    border: 1px solid rgba(248, 113, 113, 0.35);
    border-radius: 10px;
    background: rgba(127, 29, 29, 0.35);
    color: #fecaca;
    font-size: 12.5px;
    line-height: 1.35;
    overflow-wrap: anywhere;
  }

  /* `LazyColumn(contentPadding = 16/16/8/24, spacedBy(12.dp))` */
  .content {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 12px;
    min-height: 0;
    padding: 8px 16px 24px;
    overflow-y: auto;
  }

  /* `SectionHeader`: 18dp icon, 6dp gap, labelLarge semibold, onSurfaceVariant. */
  .section-header {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 2px 4px;
    color: #a1a1aa;
    font-size: 12.5px;
    font-weight: 600;
  }

  .section-header.spaced {
    margin-top: 4px;
  }

  /* `SourceItemCard`: surfaceVariant, RoundedCornerShape(12.dp), 14dp/12dp padding. */
  .source-card {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 12px;
    padding: 12px 14px;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 12px;
    background: #27272a;
  }

  /* `Icon(primaryContainer background, onPrimaryContainer tint, 40dp box)`. */
  .glyph {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border: 1px solid rgba(63, 63, 70, 0.4);
    border-radius: 10px;
    background: rgba(24, 24, 27, 0.85);
    color: #e4e4e7;
  }

  .text {
    flex: 1 1 auto;
    min-width: 0;
  }

  /* `titleSmall` bold, 1 line with ellipsis. */
  .name {
    margin: 0;
    overflow: hidden;
    font-size: 13.5px;
    font-weight: 700;
    line-height: 1.3;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  /* `bodySmall`, onSurfaceVariant, 1 line with ellipsis. */
  .origin {
    margin: 2px 0 0;
    overflow: hidden;
    color: #a1a1aa;
    font-size: 11.5px;
    line-height: 1.3;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .controls {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 4px;
  }

  .fixed-label {
    margin-right: 2px;
    color: #71717a;
    font-size: 10.5px;
    font-weight: 600;
  }

  .reorder {
    display: flex;
    align-items: center;
    gap: 2px;
  }

  /* --- switch (`Material3 Switch` → a native checkbox with a themed track) ------------------- */

  .switch {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    cursor: pointer;
  }

  .switch input {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    opacity: 0;
    pointer-events: none;
  }

  .track {
    display: inline-flex;
    align-items: center;
    width: 34px;
    height: 18px;
    padding: 2px;
    border: 1px solid rgba(63, 63, 70, 0.9);
    border-radius: 999px;
    background: rgba(39, 39, 42, 0.9);
    transition: background 0.15s, border-color 0.15s;
  }

  .thumb {
    width: 14px;
    height: 14px;
    border-radius: 50%;
    background: #71717a;
    transition: transform 0.15s, background 0.15s;
  }

  .switch input:checked + .track {
    border-color: rgba(96, 165, 250, 0.65);
    background: rgba(30, 58, 138, 0.85);
  }

  .switch input:checked + .track .thumb {
    background: #eff6ff;
    transform: translateX(16px);
  }

  .switch input:disabled + .track {
    opacity: 0.45;
    cursor: default;
  }

  .switch input:focus-visible + .track {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  /* --- `EmptySectionCard` ------------------------------------------------------------------- */

  .empty-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 20px;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 12px;
    background: rgba(39, 39, 42, 0.5);
    text-align: center;
  }

  .empty-card p {
    margin: 0;
    color: #a1a1aa;
    font-size: 12.5px;
    line-height: 1.35;
  }

  /* `OutlinedButton(RoundedCornerShape(8.dp))` */
  .empty-action {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 12px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 8px;
    background: transparent;
    color: #e4e4e7;
    font: 600 11px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .empty-action:hover {
    border-color: rgba(113, 113, 122, 0.9);
    background: rgba(39, 39, 42, 0.8);
  }

  .add-button:focus-visible,
  .empty-action:focus-visible,
  .icon-button:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  @media (prefers-reduced-motion: reduce) {
    .add-button,
    .icon-button,
    .switch input + .track,
    .switch input + .track .thumb {
      transition: none;
    }
  }
</style>
