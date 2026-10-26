<script lang="ts">
  /**
   * Comics8 Web Reader Shell: 1:1 Port of Desktop Compose `Main.kt`.
   *
   * Driven by `desktopState` from `src/state/desktop-state.svelte.ts`.
   * Layout:
   * - Reader Screen: Fullscreen `ReaderPane` with built-in immersive overlay TopBar & BottomBar.
   * - Other Screens: Top-level `TopBar` docked at top, and the active pane below (`BrowsePane`, `SeriesPane`, `HistoryPane`).
   * - Leaf screens: `Screen.SourceManager` and `Screen.Settings` render on their own, exactly like
   *   `Main.kt` does (`state.screen == Screen.SourceManager` / `Screen.Settings` bypass the top bar
   *   because both panes carry their own `TopAppBar`).
   * - Modals: `WebDavModal` (the source manager is a screen now, not a modal), plus the shell-level
   *   `PageJumpDialog` (`Main.kt` 320-336: `if (state.showPageJump)` targets `Series` with the
   *   episode page and every other screen with the reader/catalog page), and
   *   `DesktopDownloadDialog` (`if (state.showDownloadOptions)` in the Kotlin shell's dialog block).
   * - Pure black `#000000` monochrome theme matching `MonochromeTheme` from Desktop Compose.
   */
  import { onDestroy, onMount } from 'svelte';
  import { desktopState } from './state/desktop-state.svelte';
  import TopBar from './ui/TopBar.svelte';
  import PageJumpDialog from './ui/PageJumpDialog.svelte';
  import DesktopDownloadDialog from './ui/DesktopDownloadDialog.svelte';
  import BrowsePane from './views/BrowsePane.svelte';
  import DownloadsPane from './views/DownloadsPane.svelte';
  import SeriesPane from './views/SeriesPane.svelte';
  import HistoryPane from './views/HistoryPane.svelte';
  import ReaderPane from './viewer/ReaderPane.svelte';
  import SettingsPane from './views/SettingsPane.svelte';
  import SourceManagerPane from './views/SourceManagerPane.svelte';
  import SyncDialog from './ui/SyncDialog.svelte';
  import { ZipProvider } from './storage/zip-provider';

  let isDraggingOver = $state(false);
  let activeZipProvider: ZipProvider | null = null;
  let fileInputRef = $state<HTMLInputElement | null>(null);

  /**
   * Shell-level page-jump dialog, the twin of the `state.showPageJump` block in `Main.kt`.
   *
   * `Main.kt` owns the dialog for every screen (`current`/`last` switch on `Screen.Series`). One
   * surface already mounts its own copy of the same modal: `views/BrowsePane.svelte` renders it
   * inside its page bar (the Kotlin `BrowsePageBar` trigger, and its own tests mount the pane
   * standalone). Mounting a second one here would stack two identical modals over the grid, so the
   * shell skips exactly that screen. The reader's immersive dialog (`viewer/ReaderPane.svelte`) is
   * driven by the viewer's local page index, not by `showPageJump`, so it never collides.
   */
  const pageJumpScreen = $derived(desktopState.screen);
  const showShellPageJump = $derived(desktopState.showPageJump && pageJumpScreen !== 'Browse');
  const pageJumpCurrent = $derived(
    pageJumpScreen === 'Series' ? desktopState.episodePage : desktopState.page,
  );
  const pageJumpLast = $derived(
    pageJumpScreen === 'Series' ? desktopState.episodeLastPage : desktopState.lastPage,
  );

  function pageJumpTo(page: number): void {
    if (pageJumpScreen === 'Series') void desktopState.goToEpisodePage(page);
    else void desktopState.goToPage(page);
  }

  onMount(async () => {
    await desktopState.init().catch((err) => {
      console.error('Failed to init desktopState:', err);
    });
  });

  onDestroy(() => {
    activeZipProvider?.close();
  });

  // --- Local ZIP / CBZ Drag-and-Drop ---

  async function openLocalArchiveFile(file: File) {
    try {
      activeZipProvider?.close();
      const buffer = await file.arrayBuffer();
      const zip = await ZipProvider.open(buffer);
      activeZipProvider = zip;

      const pageUrls = zip.getPages().map((p) => p.url);
      if (pageUrls.length > 0) {
        desktopState.openExternalArchive(file.name, pageUrls);
      }
    } catch (err) {
      console.error('Failed to open archive:', err);
    }
  }

  function handleFileInputChange(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      void openLocalArchiveFile(file);
    }
    input.value = '';
  }

  function handleDragOver(e: DragEvent) {
    e.preventDefault();
    isDraggingOver = true;
  }

  function handleDragLeave(e: DragEvent) {
    e.preventDefault();
    isDraggingOver = false;
  }

  function handleDrop(e: DragEvent) {
    e.preventDefault();
    isDraggingOver = false;
    const file = e.dataTransfer?.files?.[0];
    if (file && (file.name.endsWith('.zip') || file.name.endsWith('.cbz'))) {
      void openLocalArchiveFile(file);
    }
  }
</script>

<svelte:window ondragover={handleDragOver} ondragleave={handleDragLeave} ondrop={handleDrop} />

<!-- Hidden input for file selection (the drop target below is the visible entry point). -->
<input
  bind:this={fileInputRef}
  type="file"
  accept=".zip,.cbz"
  class="file-input"
  onchange={handleFileInputChange}
/>

<main class="shell">
  {#if desktopState.screen === 'Reader'}
    <ReaderPane store={desktopState} />
  {:else if desktopState.screen === 'SourceManager'}
    <SourceManagerPane store={desktopState} />
  {:else if desktopState.screen === 'Settings'}
    <SettingsPane store={desktopState} />
  {:else}
    <TopBar
      store={desktopState}
      onOpenSourceManager={() => desktopState.openSourceManager()}
      onOpenSettings={() => {
        desktopState.openSettings();
      }}
    />
    <div class="surface">
      {#if desktopState.screen === 'Browse'}
        <BrowsePane store={desktopState} />
      {:else if desktopState.screen === 'Series'}
        <SeriesPane store={desktopState} />
      {:else if desktopState.screen === 'History'}
        <HistoryPane store={desktopState} />
      {:else if desktopState.screen === 'Downloads'}
        <DownloadsPane store={desktopState} />
      {/if}
    </div>
  {/if}

  <!-- Modals -->
  {#if showShellPageJump}
    <PageJumpDialog
      current={pageJumpCurrent}
      last={pageJumpLast}
      onDismiss={() => desktopState.togglePageJump(false)}
      onConfirm={pageJumpTo}
    />
  {/if}

  {#if desktopState.showSyncDialog}
    <SyncDialog onDismiss={() => desktopState.closeSyncDialog()} />
  {/if}

  <!-- `if (state.showDownloadOptions) DesktopDownloadDialog(series = state.series, episodes = state.downloadCatalog, …)` -->
  {#if desktopState.showDownloadOptions}
    <DesktopDownloadDialog store={desktopState} />
  {/if}


  <!-- Drag-and-drop overlay indicator -->
  {#if isDraggingOver}
    <div class="drop-overlay">
      <p class="drop-title">ZIP / CBZ 파일을 여기에 놓으세요</p>
      <p class="drop-hint">만화 압축 파일이 즉시 뷰어로 열립니다</p>
    </div>
  {/if}
</main>

<style>
  /*
   * Shell frame, the `Main.kt` `Column(Modifier.fillMaxSize())`: a full-window, non-scrolling black
   * surface. Kept as scoped CSS instead of Tailwind utilities — the shell's markup is rendered from
   * `ReaderApp.svelte` while the utility classes it used to carry (`h-screen flex-1 min-h-0 …`) were
   * only emitted for the settings entry, so the reader could ship without any of them.
   */
  .shell {
    display: flex;
    flex-direction: column;
    width: 100vw;
    height: 100vh;
    overflow: hidden;
    background: #000000;
    color: #fafafa;
    font-family: var(--font-sans, system-ui, sans-serif);
    user-select: none;
  }

  /* `Modifier.weight(1f)` under the docked top bar; each pane scrolls inside this box. */
  .surface {
    position: relative;
    flex: 1 1 auto;
    min-height: 0;
    overflow: hidden;
  }

  .file-input {
    display: none;
  }

  /* Drag target hint: the Compose shell has no drop zone, this is the web-only affordance. */
  .drop-overlay {
    position: fixed;
    inset: 0;
    z-index: 50;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    border: 4px dashed #71717a;
    background: rgba(0, 0, 0, 0.8);
    pointer-events: none;
    backdrop-filter: blur(4px);
  }

  .drop-title {
    margin: 0 0 8px;
    color: #ffffff;
    font-size: 20px;
    font-weight: 700;
  }

  .drop-hint {
    margin: 0;
    color: #a1a1aa;
    font-size: 14px;
  }

  :global(body) {
    margin: 0;
    padding: 0;
    background-color: #000000;
    color: #fafafa;
    overflow: hidden;
  }
</style>
