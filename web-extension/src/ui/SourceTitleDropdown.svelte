<script lang="ts">
  /**
   * Active-source title with the source switcher menu, ported from the private
   * `SourceTitleDropdown` of `desktop/src/main/kotlin/ui/components/TopBar.kt` (the Browser row's
   * title slot).
   *
   * Mapping notes (deliberate, not accidental):
   * - Kotlin takes `(activeSourceId, sources, registry, onSelect, onOpenSourceManager,
   *   onOpenSettings)`. The web bar owns only the store, so this component reads
   *   `store.activeSourceId` / `store.installedSources` and calls `store.setActiveSource`. The two
   *   remaining actions stay props: source management and settings are shell navigation, not store
   *   state (`Screen.SourceManager` is a routed screen again — `views/SourceManagerPane.svelte`,
   *   opened by `DesktopStore.openSourceManager`).
   * - The Kotlin leading icon is `ComicSource.resolveSourceType()` → Folder / Dns / Cloud /
   *   Description. The web registry's `SourceSummary` carries no type, so the icon is derived from
   *   the one thing that identifies it: `local` is the local library, `network-…` is a network
   *   library (`SourceType.kt`), everything else is a JS plugin (FileCode instead of Description).
   * - `ComicSource.displayTitle(strings)` renders the `local` source as the generic storage label;
   *   `SourceSummary.displayName` is used as-is everywhere else.
   * - Dismissing the menu (`onDismissRequest`) is a backdrop click, a second click on the title, or
   *   Escape — the same three ways a Compose `DropdownMenu` closes.
   */

  import { tick } from 'svelte';
  import {
    Check,
    ChevronDown,
    Cloud,
    Database,
    FileCode,
    Folder,
    Settings,
  } from '@lucide/svelte';

  import { desktopState } from '../state/desktop-state.svelte';
  import type { DesktopStore } from '../state/desktop-state.svelte';
  import type { SourceSummary } from '../sources/registry';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
    /** Opens the source manager (`DesktopViewModel.openSourceManager`). */
    onOpenSourceManager?: () => void;
    /** Opens settings (`DesktopViewModel.openSettings`). */
    onOpenSettings?: () => void;
  }

  let {
    store = desktopState,
    onOpenSourceManager,
    onOpenSettings,
  }: Props = $props();

  /** `LocalStrings.navStorage` — the label of the `local` source (`displayTitle`). */
  const LOCAL_LABEL = '저장소';

  let expanded = $state(false);

  const sources = $derived(store.installedSources);
  const activeSource = $derived(
    sources.find((source) => source.id === store.activeSourceId) ?? null,
  );
  const label = $derived(activeSource?.displayName || LOCAL_LABEL);
  /** The trigger's leading glyph, derived from the same rule as the menu rows below. */
  const ActiveGlyph = $derived(sourceIcon(store.activeSourceId));

  /**
   * `resolveSourceType()`: `local` is LOCAL (Folder), `network-…` is a network library
   * (WEBDAV → Cloud), and a plugin is JS (FileCode).
   */
  function sourceIcon(sourceId: string | null | undefined): typeof Folder {
    if (!sourceId || sourceId === 'local') return Folder;
    if (sourceId.startsWith('network-')) return Cloud;
    return FileCode;
  }

  function toggle(): void {
    expanded = !expanded;
  }

  function openManager(): void {
    expanded = false;
    onOpenSourceManager?.();
  }

  function openSettings(): void {
    expanded = false;
    onOpenSettings?.();
  }

  function select(source: SourceSummary): void {
    expanded = false;
    void store.setActiveSource(source.id);
  }

  /** `DropdownMenu(onDismissRequest)` — Escape closes without choosing. */
  function onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || !expanded) return;
    event.preventDefault();
    event.stopPropagation();
    expanded = false;
  }

  // Compose focuses the menu on open; the web menu is focusable only once it is in the DOM.
  let menu: HTMLElement | undefined = $state();
  $effect(() => {
    if (!expanded) return;
    void tick().then(() => menu?.focus());
  });
</script>

<svelte:window onkeydown={onKeydown} />

<div class="source-title">
  <button
    type="button"
    class="trigger"
    title="소스 선택"
    aria-haspopup="menu"
    aria-expanded={expanded}
    onclick={toggle}
  >
    <span class="active-icon">
      <ActiveGlyph size={18} aria-hidden="true" />
    </span>
    <span class="label">{label}</span>
    <span class="caret">
      <ChevronDown size={18} aria-hidden="true" />
    </span>
  </button>

  {#if expanded}
    <button
      type="button"
      class="menu-backdrop"
      aria-label="소스 선택 메뉴 닫기"
      onclick={() => (expanded = false)}
    ></button>

    <div class="menu" role="menu" tabindex="-1" bind:this={menu} data-testid="source-menu">
      {#if sources.length > 0}
        {#each sources as source (source.id)}
          {@const ItemIcon = sourceIcon(source.id)}
          <button
            type="button"
            class="menu-item"
            class:selected={source.id === store.activeSourceId}
            role="menuitemradio"
            aria-checked={source.id === store.activeSourceId}
            onclick={() => select(source)}
          >
            <span class="item-icon">
              <ItemIcon size={18} aria-hidden="true" />
            </span>
            <span class="item-label">{source.displayName}</span>
            {#if source.id === store.activeSourceId}
              <span class="item-check" aria-label="선택됨">
                <Check size={18} aria-hidden="true" />
              </span>
            {/if}
          </button>
        {/each}

        <div class="divider" role="separator"></div>
      {/if}

      <button type="button" class="menu-item" role="menuitem" onclick={openManager}>
        <span class="item-icon">
          <Database size={18} aria-hidden="true" />
        </span>
        <span class="item-label">소스 관리</span>
      </button>

      <button type="button" class="menu-item" role="menuitem" onclick={openSettings}>
        <span class="item-icon">
          <Settings size={18} aria-hidden="true" />
        </span>
        <span class="item-label">설정</span>
      </button>
    </div>
  {/if}
</div>

<style>
  /* `Box(modifier.weight(1f))` — the title takes the row's free space. */
  .source-title {
    position: relative;
    display: flex;
    flex: 1 1 auto;
    align-items: center;
    min-width: 0;
  }

  /* `Row(clip(RoundedCornerShape(8.dp)).clickable { expanded = true }.padding(4dp, 2dp))`. */
  .trigger {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    padding: 2px 4px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #fafafa;
    cursor: pointer;
    transition: background 0.15s;
  }

  .trigger:hover {
    background: rgba(39, 39, 42, 0.7);
  }

  /* `tint = MaterialTheme.colorScheme.primary` */
  .active-icon {
    display: inline-flex;
    flex: 0 0 auto;
    color: #ffffff;
  }

  .label {
    min-width: 0;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    font-size: 17px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }

  /* `tint = MaterialTheme.colorScheme.onSurfaceVariant` */
  .caret {
    display: inline-flex;
    flex: 0 0 auto;
    color: #a1a1aa;
  }

  .menu-backdrop {
    position: fixed;
    inset: 0;
    z-index: 40;
    padding: 0;
    border: none;
    background: transparent;
    cursor: default;
  }

  /* `DropdownMenu` with 12dp corners and an outlineVariant hairline. */
  .menu {
    position: absolute;
    top: calc(100% + 4px);
    left: 0;
    z-index: 41;
    display: flex;
    flex-direction: column;
    min-width: 216px;
    max-width: min(320px, 90vw);
    max-height: 60vh;
    overflow-y: auto;
    padding: 4px 0;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 12px;
    background: #18181b;
    box-shadow: 0 18px 44px rgba(0, 0, 0, 0.6);
  }

  .menu:focus {
    outline: none;
  }

  .menu-item {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 9px 14px;
    border: none;
    background: transparent;
    color: #fafafa;
    font: 400 13px/1.25 var(--font-sans, system-ui, sans-serif);
    text-align: left;
    cursor: pointer;
  }

  .menu-item:hover {
    background: rgba(39, 39, 42, 0.9);
  }

  .item-label {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  /* unselected: onSurfaceVariant · selected: primary */
  .item-icon {
    display: inline-flex;
    flex: 0 0 auto;
    color: #a1a1aa;
  }

  .menu-item.selected {
    font-weight: 700;
    color: #ffffff;
  }

  .menu-item.selected .item-icon,
  .item-check {
    display: inline-flex;
    flex: 0 0 auto;
    color: #ffffff;
  }

  /* `HorizontalDivider(color = outlineVariant.copy(alpha = 0.3f), padding(vertical = 4dp))`. */
  .divider {
    height: 0;
    margin: 4px 0;
    border-top: 1px solid rgba(39, 39, 42, 0.3);
  }
</style>
