<script lang="ts">
  /**
   * Settings screen, ported 1:1 from `desktop/src/main/kotlin/ui/settings/`.
   *
   * Kotlin contract (`SettingsPane.kt`): one `Scaffold` whose `TopAppBar` carries the back arrow
   * (`viewModel.closeSettings()`) and the title of the current category, over a `LazyColumn` whose
   * single item is either the **category hub** (five `SettingsCategoryCard`s when
   * `state.selectedSettingsCategory == null`) or the **sub-pane** that category routes to
   * (`GeneralSettingsPane` / `ReaderSettingsPane` / `SyncBackupSettingsPane` / `NetworkSettingsPane`
   * / `AboutSettingsPane`).
   *
   * Port mapping notes (deliberate, not accidental):
   * - Driven by the ported store: `store.selectedSettingsCategory` is
   *   `DesktopUiState.selectedSettingsCategory`, `store.selectSettingsCategory(category)` and
   *   `store.closeSettingsCategory()` are `DesktopViewModel.selectSettingsCategory(null|cat)`, and
   *   `store.closeSettings()` is the Kotlin action verbatim (pop the category, else leave the
   *   screen) — so the hub's back arrow returns to Browse while a sub-pane's own back arrow only
   *   returns to the hub, exactly like the desktop.
   * - `SettingsComponents.kt` is inlined as the shared pieces of this file (`SectionTitle`,
   *   `SettingCard`, `SettingsCategoryCard`, `SelectField`, `RadioOption`, `Notice`) instead of being
   *   a second component module: on the Kotlin side it is a bag of `@Composable` helpers, and every
   *   one of them is used by exactly the panes rendered here.
   * - The hub cards keep the Kotlin glyphs (`Language` → `Languages`, `MenuBook` → `BookOpen`,
   *   `CloudSync`, `Public` → `Globe`/`HardDrive`, `Info`), the trailing `KeyboardArrowRight`
   *   (`ChevronRight`) and the same summary rules: the resolved language, `viewMode · direction`,
   *   the sync state, the network/WebDAV description and `labelAppVersion`.
   * - `GeneralSettingsPane`'s `SettingDropdownRow` is a native `<select>`: a keyboard-accessible
   *   dropdown is what the Compose `DropdownMenu` is, and the shell has no popup component.
   * - `ReaderSettingsPane`'s three `RadioButton` groups are the three chip rows below; the Kotlin
   *   labels/descriptions (`ViewMode.displayLabel` / `displayDescription` …) come from
   *   `src/state/desktop-state.svelte.ts` so both clients show identical copy.
   * - `SyncBackupSettingsPane`'s two dialogs (`PairingCodeDialog`, `ConnectDeviceDialog`) are the
   *   two inline blocks of the sync card instead of two nested modals: the desktop opens a dialog
   *   because a Compose window cannot inline them, while this pane already is a full screen. The
   *   wire calls are the same `SyncClient` endpoints (`/pair/request`, `/pair/confirm`).
   * - `NetworkSettingsPane`'s proxy rows are kept (`DesktopViewModel.setProxyType` /
   *   `setCustomProxy` / `testProxyConnection`) next to the WebDAV connection the web shell actually
   *   browses: a browser extension cannot open a raw HTTP/SOCKS socket, so the custom proxy form is
   *   not offered and the connection test is a request through the background worker.
   * - `AboutSettingsPane`'s update button and release history have no web twin (the extension is
   *   updated by the browser store). The version, the licence and the "캐시 삭제" action are kept;
   *   the cache it clears is the one this client holds (`store.clearImageCache()`).
   * - Pure black `#000000` + zinc borders, the shell's `MonochromeTheme` (`ReaderApp.svelte`).
   */

  import { onMount } from 'svelte';
  import {
    ArrowLeft,
    BookOpen,
    Check,
    ChevronDown,
    ChevronRight,
    CircleAlert,
    Cloud,
    CloudSync,
    Copy,
    Download,
    ExternalLink,
    Globe,
    HardDrive,
    History,
    Info,
    KeyRound,
    Languages,
    Link2,
    LoaderCircle,
    RefreshCw,
    Save,
    Smartphone,
    Timer,
    Upload,
    X,
  } from 'lucide-svelte';

  import SyncPairingDialog from '../ui/SyncPairingDialog.svelte';
  import SyncConnectDialog from '../ui/SyncConnectDialog.svelte';
  import { favoritesStore } from '../data/favorites-store.svelte';
  import { historyStore } from '../data/history-store.svelte';
  import { describeError } from '../messaging';
  import {
    appLanguageLabel,
    desktopState,
    readDirectionShortLabel,
    resolveAppLanguage,
    settingsTitleFor,
    splitModeDescription,
    splitModeLabel,
    viewModeDescription,
    viewModeLabel,
    viewModeShortLabel,
  } from '../state/desktop-state.svelte';
  import type {
    AppLanguage,
    DesktopStore,
    ReadDirection,
    SettingsCategory,
    SplitMode,
    ViewMode,
  } from '../state/desktop-state.svelte';
  import { WebDavProvider, type WebDavConfig } from '../storage/webdav-provider';
  import { formatAbsoluteTime, formatCountdown, formatRelativeTime } from './format';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
    /** Opens the shell's WebDAV browser (the old view's `onOpenWebDavLibrary`). */
    onOpenWebDavLibrary?: () => void;
  }

  let { store = desktopState, onOpenWebDavLibrary }: Props = $props();

  /** `strings.labelAppName` / `labelAppVersion(DesktopVersion.VERSION_NAME)`. */
  const APP_NAME = 'Comics8';
  /** `manifest.json`'s `version`, the web twin of `DesktopVersion.VERSION_NAME`. */
  const APP_VERSION = '0.2.48';
  /** Storage key shared with `src/ui/WebDavModal.svelte`; changing it would split the connection. */
  const WEBDAV_CONFIG_KEY = 'comics8.webdav.config';

  // --- webdav state (declared before the hub summaries that read it) ------------------------------

  let webdavUrl = $state('');
  let webdavUsername = $state('');
  let webdavPassword = $state('');
  let webdavBusy = $state(false);
  let webdavResult = $state<{ kind: 'idle' | 'ok' | 'error'; text: string }>({
    kind: 'idle',
    text: '',
  });

  // --- hub --------------------------------------------------------------------------------------

  const category: SettingsCategory | null = $derived(store.selectedSettingsCategory);
  const isHub: boolean = $derived(category === null);

  /** `if (appLanguage == AUTO) strings.langAuto else appLanguage.nativeName`. */
  const languageValue: string = $derived(
    store.appLanguage === 'AUTO'
      ? `자동 (Auto) · ${appLanguageLabel(resolveAppLanguage('AUTO'))}`
      : appLanguageLabel(store.appLanguage),
  );

  /** `if (syncState.lastSyncedAt > 0) strings.statusCloudConnected else strings.descSyncAndBackup`. */
  const syncSummary: string = $derived(
    store.syncState.lastSyncedAt > 0
      ? `클라우드 연결됨 · ${formatRelativeTime(store.syncState.lastSyncedAt)}`
      : '클라우드 동기화, 기기 연동, 데이터 백업 및 복원',
  );

  /** `strings.descNetworkAndDownload`, replaced by the saved WebDAV host when there is one. */
  const networkSummary: string = $derived(
    webdavUrl.trim() === ''
      ? 'WebDAV 원격 스토리지 · 프록시 및 네트워크 연결 설정'
      : `WebDAV: ${webdavHost(webdavUrl)}`,
  );

  /** The five hub cards of `SettingsPane.kt`, in the Kotlin order. */
  const CATEGORY_CARDS: {
    category: SettingsCategory;
    title: string;
    summary: string;
    icon: typeof Info;
  }[] = $derived([
    {
      category: 'GENERAL',
      title: '일반 설정',
      summary: `앱 표시 언어: ${languageValue}`,
      icon: Languages,
    },
    {
      category: 'READER',
      title: '뷰어 기본 설정',
      summary: `${viewModeShortLabel(store.viewMode)} · ${readDirectionShortLabel(store.readDirection)}`,
      icon: BookOpen,
    },
    { category: 'SYNC_BACKUP', title: '동기화 및 백업', summary: syncSummary, icon: CloudSync },
    {
      category: 'NETWORK_DOWNLOAD',
      title: '네트워크 설정',
      summary: networkSummary,
      icon: webdavUrl.trim() === '' ? Globe : HardDrive,
    },
    { category: 'ABOUT', title: '앱 정보 및 기타', summary: `버전 v${APP_VERSION}`, icon: Info },
  ]);

  // --- general -----------------------------------------------------------------------------------

  /** `AppLanguage.entries`. */
  const LANGUAGE_OPTIONS: AppLanguage[] = ['AUTO', 'KO', 'EN', 'JA', 'ZH_CN', 'ZH_TW'];

  // --- reader defaults ---------------------------------------------------------------------------

  const VIEW_MODE_OPTIONS: ViewMode[] = ['SCROLL', 'PAGE', 'DUAL'];
  const READ_DIRECTION_OPTIONS: ReadDirection[] = ['RIGHT_TO_LEFT', 'LEFT_TO_RIGHT'];
  const SPLIT_MODE_OPTIONS: SplitMode[] = ['FIT', 'SLICE'];

  // --- sync --------------------------------------------------------------------------------------

  type Notice = { kind: 'ok' | 'error' | 'info'; text: string };
  type Busy = null | 'sync' | 'key';

  let busy = $state<Busy>(null);
  let notice = $state<Notice | null>(null);

  /** `SyncBackupSettingsPane.kt`'s `showPairingCodeDialog` / `showConnectDialog`. */
  let showPairingDialog = $state(false);
  let showConnectDialog = $state(false);
  let showAdvancedSync = $state(false);
  let copied = $state(false);

  /** `status.syncKey.isNotBlank()` — a key exists (minted locally or installed by pairing). */
  const keyConfigured: boolean = $derived(store.syncState.syncKey.trim() !== '');
  /** `if (syncState.lastSyncedAt > 0)`: only a server-acknowledged key counts as connected. */
  const connected: boolean = $derived(store.syncState.lastSyncedAt > 0);

  /**
   * `strings.labelLastSyncedAt(SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))` / `labelNoSyncHistory`.
   *
   * The Kotlin pane prints an absolute timestamp here (the dialog prints the same value); the
   * relative form is what the hub summary uses, so this pane keeps the absolute one.
   */
  const lastSyncLabel: string = $derived(
    store.syncState.lastSyncedAt > 0
      ? `마지막 동기화: ${formatAbsoluteTime(store.syncState.lastSyncedAt)}`
      : '동기화 이력 없음',
  );

  /** `syncState.syncKey.ifBlank { strings.statusIssuingKey }`. */
  const masterKeyLabel: string = $derived(store.syncState.syncKey.trim() || '키 발급 중...');

  // --- about -------------------------------------------------------------------------------------

  let cacheNotice = $state<string | null>(null);

  // ---------------------------------------------------------------------------------------------
  // lifecycle
  // ---------------------------------------------------------------------------------------------

  onMount(() => {
    void bootstrap();
  });

  async function bootstrap(): Promise<void> {
    await Promise.allSettled([store.loadSyncState(), loadWebDavConfig()]);
  }

  /** `chrome.storage.local` is untyped here (`{}`), so read through an index signature. */
  async function loadWebDavConfig(): Promise<void> {
    if (typeof chrome === 'undefined' || !chrome.storage?.local) return;
    try {
      const stored = await chrome.storage.local.get(WEBDAV_CONFIG_KEY);
      const config = (stored as Record<string, unknown>)[WEBDAV_CONFIG_KEY];
      if (!config || typeof config !== 'object') return;
      const record = config as Partial<WebDavConfig>;
      webdavUrl = typeof record.url === 'string' ? record.url : '';
      webdavUsername = typeof record.username === 'string' ? record.username : '';
      webdavPassword = typeof record.password === 'string' ? record.password : '';
      if (webdavUrl) {
        webdavResult = { kind: 'idle', text: '저장된 설정이 있습니다. 연결을 확인해 보세요.' };
      }
    } catch {
      // A missing/unreadable config simply leaves the form empty.
    }
  }


  // ---------------------------------------------------------------------------------------------
  // navigation (`DesktopViewModel.closeSettings` / `selectSettingsCategory`)
  // ---------------------------------------------------------------------------------------------

  /** The sub-pane's own back arrow: `selectSettingsCategory(null)`, i.e. always the hub. */
  function closeCategory(): void {
    notice = null;
    store.closeSettingsCategory();
  }

  /** The hub's back arrow: `viewModel.closeSettings()` → returns to Browse. */
  function closeSettings(): void {
    notice = null;
    store.closeSettings();
  }

  // ---------------------------------------------------------------------------------------------
  // general
  // ---------------------------------------------------------------------------------------------

  /** `DesktopViewModel.setAppLanguage(language)`. */
  function setLanguage(language: AppLanguage): void {
    store.setAppLanguage(language);
  }

  // ---------------------------------------------------------------------------------------------
  // reader defaults
  // ---------------------------------------------------------------------------------------------

  function setViewMode(mode: ViewMode): void {
    store.setViewMode(mode);
  }

  function setReadDirection(direction: ReadDirection): void {
    store.setReadDirection(direction);
  }

  function setSplitMode(mode: SplitMode): void {
    store.setSplitMode(mode);
  }

  // ---------------------------------------------------------------------------------------------
  // sync actions
  // ---------------------------------------------------------------------------------------------

  /** `strings.actionSyncNow` — the pull-then-push pair of `SyncDialog` / the Kotlin `syncNow`. */
  async function syncNow(): Promise<void> {
    busy = 'sync';
    try {
      const result = await store.syncNow();
      notice = { kind: result.ok ? 'ok' : 'error', text: result.message };
    } finally {
      busy = null;
    }
  }

  /** `DesktopViewModel.generateNewSyncKey`. */
  async function generateNewSyncKey(): Promise<void> {
    busy = 'key';
    notice = null;
    try {
      await store.generateNewSyncKey();
      notice = { kind: 'ok', text: '새 마스터 복구 키를 발급했습니다.' };
    } catch (error) {
      notice = { kind: 'error', text: describeError(error) };
    } finally {
      busy = null;
    }
  }

  async function copyToClipboard(value: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      copied = true;
      setTimeout(() => {
        copied = false;
      }, 1600);
    } catch {
      notice = { kind: 'error', text: '클립보드에 복사하지 못했습니다.' };
    }
  }

  // ---------------------------------------------------------------------------------------------
  // backup / restore
  // ---------------------------------------------------------------------------------------------

  let backupFileInput: HTMLInputElement | undefined = $state();

  function triggerImportBackup(): void {
    backupFileInput?.click();
  }

  async function handleBackupFile(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      const data = JSON.parse(text);
      if (Array.isArray(data.favorites)) {
        await favoritesStore.applyRemoteFavorites(data.favorites);
      }
      if (Array.isArray(data.history) || Array.isArray(data.readEpisodes)) {
        await historyStore.applyRemoteHistory(data.history ?? [], data.readEpisodes ?? []);
      }
      notice = { kind: 'ok', text: '백업 데이터를 성공적으로 복원했습니다.' };
    } catch (e) {
      notice = { kind: 'error', text: `백업 복원 실패: ${describeError(e)}` };
    } finally {
      input.value = '';
    }
  }

  /**
   * `DesktopViewModel.exportBackup(file)` / `repository.exportBackupJson()`.
   */
  async function exportBackup(): Promise<void> {
    await Promise.allSettled([favoritesStore.init(), historyStore.init()]);
    const payload = {
      favorites: favoritesStore.getFavorites(),
      history: historyStore.getHistory(),
      readEpisodes: historyStore.readEpisodes,
      exportedAt: Date.now(),
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `comics8_backup_${backupStamp()}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
    notice = { kind: 'ok', text: '백업 파일을 내려받았습니다.' };
  }

  async function importBackup(): Promise<void> {
    triggerImportBackup();
  }

  // ---------------------------------------------------------------------------------------------
  // webdav actions
  // ---------------------------------------------------------------------------------------------

  function currentWebDavConfig(): WebDavConfig {
    return {
      url: webdavUrl.trim(),
      username: webdavUsername.trim() || undefined,
      password: webdavPassword || undefined,
    };
  }

  let updateFeedback = $state<string | null>(null);

  async function checkUpdate(): Promise<void> {
    updateFeedback = null;
    const res = await store.checkForUpdate(true);
    updateFeedback = res;
  }

  async function saveWebDavConfig(): Promise<void> {
    if (typeof chrome === 'undefined' || !chrome.storage?.local) return;
    try {
      await chrome.storage.local.set({ [WEBDAV_CONFIG_KEY]: currentWebDavConfig() });
    } catch {
      // Quota/serialisation failures are reported by the connection test instead.
    }
  }

  async function testWebDav(): Promise<void> {
    if (!webdavUrl.trim()) {
      webdavResult = { kind: 'error', text: 'WebDAV 서버 URL을 입력하세요.' };
      return;
    }

    webdavBusy = true;
    webdavResult = { kind: 'idle', text: '연결 확인 중...' };
    try {
      const provider = new WebDavProvider(currentWebDavConfig());
      const entries = await provider.test();
      await saveWebDavConfig();
      webdavResult = { kind: 'ok', text: `연결 성공 — 항목 ${entries.length}개를 읽었습니다.` };
    } catch (error) {
      webdavResult = { kind: 'error', text: describeError(error) };
    } finally {
      webdavBusy = false;
    }
  }

  async function openWebDavLibrary(): Promise<void> {
    await saveWebDavConfig();
    onOpenWebDavLibrary?.();
  }

  // ---------------------------------------------------------------------------------------------
  // about
  // ---------------------------------------------------------------------------------------------

  /** `strings.sectionAppInfo`'s "캐시 삭제" action. */
  function clearCache(): void {
    const dropped = store.clearImageCache();
    cacheNotice =
      dropped > 0 ? `캐시를 삭제했습니다. (항목 ${dropped}개)` : '삭제할 캐시가 없습니다.';
  }

  /** `SimpleDateFormat("yyyyMMdd_HHmm")` of the Kotlin export file name. */
  function backupStamp(now: Date = new Date()): string {
    const pad = (value: number): string => String(value).padStart(2, '0');
    return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(
      now.getHours(),
    )}${pad(now.getMinutes())}`;
  }

  /** Host of a WebDAV URL, for the hub summary line. */
  function webdavHost(url: string): string {
    try {
      return new URL(url).host;
    } catch {
      return url;
    }
  }
</script>

<section class="pane">
  {#if isHub}
    <!-- `TopAppBar(navigationIcon = ArrowBack → viewModel::closeSettings, title = navSettings)` -->
    <header class="top-bar">
      <button
        type="button"
        class="icon-button"
        aria-label="뒤로가기"
        title="뒤로가기"
        onclick={closeSettings}
      >
        <ArrowLeft size={20} aria-hidden="true" />
      </button>
      <h1 class="title">설정</h1>
    </header>
  {:else}
    <!-- The sub-pane's own bar: `selectSettingsCategory(null)` returns to the hub. -->
    <header class="top-bar">
      <button
        type="button"
        class="icon-button"
        aria-label="뒤로가기"
        title="뒤로가기"
        onclick={closeCategory}
      >
        <ArrowLeft size={20} aria-hidden="true" />
      </button>
      <h1 class="title">{settingsTitleFor(category)}</h1>
    </header>
  {/if}

  <!-- `LazyColumn(PaddingValues(start = 16, top = 8, end = 16, bottom = 24), spacedBy(14.dp))` -->
  <div class="content" data-category={category ?? 'HUB'}>
    {#if isHub}
      <!-- ── Main category hub ──────────────────────────────────────────────────────────────── -->
      {#each CATEGORY_CARDS as card (card.category)}
        <button
          type="button"
          class="category-card"
          data-category={card.category}
          onclick={() => store.selectSettingsCategory(card.category)}
        >
          <span class="category-glyph">
            <card.icon size={22} aria-hidden="true" />
          </span>
          <span class="category-text">
            <span class="category-title">{card.title}</span>
            {#if card.summary}
              <span class="category-summary">{card.summary}</span>
            {/if}
          </span>
          <ChevronRight size={20} class="category-chevron" aria-hidden="true" />
        </button>
      {/each}
    {:else}
      {#if notice}
        <div
          class="notice"
          class:ok={notice.kind === 'ok'}
          class:error={notice.kind === 'error'}
          role="status"
        >
          {#if notice.kind === 'ok'}
            <Check size={15} aria-hidden="true" />
          {:else if notice.kind === 'error'}
            <CircleAlert size={15} aria-hidden="true" />
          {:else}
            <Link2 size={15} aria-hidden="true" />
          {/if}
          <span>{notice.text}</span>
          <button
            type="button"
            class="notice-close"
            aria-label="알림 닫기"
            onclick={() => (notice = null)}
          >
            <X size={14} aria-hidden="true" />
          </button>
        </div>
      {/if}

      {#if category === 'GENERAL'}
        <!-- ── GeneralSettingsPane.kt ───────────────────────────────────────────────────────── -->
        <div class="section-title">
          <Languages size={18} aria-hidden="true" />
          <span>일반</span>
        </div>

        <div class="setting-card">
          <!-- `SettingDropdownRow(title = labelLanguage, options = AppLanguage.entries)` -->
          <div class="setting-row">
            <div class="setting-label">
              <span class="setting-name">앱 표시 언어</span>
              <span class="setting-hint">
                {store.appLanguage === 'AUTO'
                  ? `시스템 언어를 따릅니다 (${appLanguageLabel(resolveAppLanguage('AUTO'))})`
                  : '앱 전체에 적용되는 표시 언어입니다.'}
              </span>
            </div>

            <div class="dropdown">
              <select
                class="select"
                aria-label="앱 표시 언어"
                value={store.appLanguage}
                onchange={(event) => setLanguage(event.currentTarget.value as AppLanguage)}
              >
                {#each LANGUAGE_OPTIONS as option (option)}
                  <option value={option}>{appLanguageLabel(option)}</option>
                {/each}
              </select>
              <ChevronDown size={16} class="dropdown-glyph" aria-hidden="true" />
            </div>
          </div>
        </div>
      {:else if category === 'READER'}
        <!-- ── ReaderSettingsPane.kt ────────────────────────────────────────────────────────── -->
        <div class="section-title">
          <BookOpen size={18} aria-hidden="true" />
          <span>뷰어 기본 설정</span>
        </div>

        <div class="setting-card">
          <p class="group-label">기본 보기 모드</p>
          <div class="chips" role="group" aria-label="기본 보기 모드">
            {#each VIEW_MODE_OPTIONS as mode (mode)}
              {@const selected = store.viewMode === mode}
              <button
                type="button"
                class="chip view-mode"
                class:selected
                aria-pressed={selected}
                data-view-mode={mode}
                title={viewModeDescription(mode)}
                onclick={() => setViewMode(mode)}
              >
                {viewModeLabel(mode)}
              </button>
            {/each}
          </div>
          <p class="hint">
            {viewModeDescription(store.viewMode)}
          </p>

          <span class="divider spaced" aria-hidden="true"></span>

          <p class="group-label">읽기 방향 (단면/양면)</p>
          <div class="chips" role="group" aria-label="읽기 방향">
            {#each READ_DIRECTION_OPTIONS as direction (direction)}
              {@const selected = store.readDirection === direction}
              <button
                type="button"
                class="chip read-direction"
                class:selected
                aria-pressed={selected}
                data-read-direction={direction}
                title={readDirectionShortLabel(direction)}
                onclick={() => setReadDirection(direction)}
              >
                {direction === 'RIGHT_TO_LEFT' ? '오른쪽 → 왼쪽' : '왼쪽 → 오른쪽'}
              </button>
            {/each}
          </div>
          <p class="hint">
            {store.readDirection === 'RIGHT_TO_LEFT'
              ? '우측에서 좌측으로 넘김 (일본 만화 등)'
              : '좌측에서 우측으로 넘김 (웹툰/일반 서적)'}
          </p>

          <span class="divider spaced" aria-hidden="true"></span>

          <p class="group-label">와이드 분할 모드 (단면 보기)</p>
          <div class="chips" role="group" aria-label="와이드 분할 모드">
            {#each SPLIT_MODE_OPTIONS as mode (mode)}
              {@const selected = store.splitMode === mode}
              <button
                type="button"
                class="chip split-mode"
                class:selected
                aria-pressed={selected}
                data-split-mode={mode}
                onclick={() => setSplitMode(mode)}
              >
                {splitModeLabel(mode)}
              </button>
            {/each}
          </div>
          <p class="hint">{splitModeDescription(store.splitMode)}</p>
        </div>
      {:else if category === 'SYNC_BACKUP'}
        <!-- ── SyncBackupSettingsPane.kt ────────────────────────────────────────────────────── -->
        <!-- 섹션 1: 클라우드 동기화 및 기기 연결 -->
        <div class="section-title">
          <CloudSync size={18} aria-hidden="true" />
          <span>클라우드 동기화 및 기기 연결</span>
        </div>

        <div class="setting-card">
          <!-- 1. 동기화 상태 및 즉시 동기화 버튼 -->
          <div class="sync-head">
            <div class="sync-status">
              <span class="status-pill" class:on={connected}>
                <span class="dot" aria-hidden="true"></span>
                {store.syncState.isSyncing
                  ? '동기화 진행 중...'
                  : connected
                    ? '클라우드 동기화 준비됨'
                    : '미연결'}
              </span>
              <span class="sync-time">{lastSyncLabel}</span>
            </div>

            <button
              type="button"
              class="btn primary"
              onclick={syncNow}
              disabled={busy !== null || store.syncState.isSyncing}
            >
              {#if store.syncState.isSyncing}
                <LoaderCircle size={14} class="spin" aria-hidden="true" /> 동기화 중...
              {:else}
                <RefreshCw size={14} aria-hidden="true" /> 동기화
              {/if}
            </button>
          </div>

          {#if notice}
            <p class="inline-notice {notice.kind}" role="status">
              {#if notice.kind === 'error'}
                <CircleAlert size={13} aria-hidden="true" />
              {:else if notice.kind === 'ok'}
                <Check size={13} aria-hidden="true" />
              {:else}
                <Info size={13} aria-hidden="true" />
              {/if}
              {notice.text}
            </p>
          {/if}

          <!-- 2. 기기 페어링 액션 버튼들 -->
          <div class="pair-action-buttons">
            <button
              type="button"
              class="btn tonal full-width"
              onclick={() => (showPairingDialog = true)}
            >
              <Smartphone size={16} aria-hidden="true" />
              <span>새 기기 연결 (페어링 코드 생성)</span>
            </button>

            <button
              type="button"
              class="btn outlined full-width"
              onclick={() => (showConnectDialog = true)}
            >
              <KeyRound size={16} aria-hidden="true" />
              <span>다른 기기 연결</span>
            </button>
          </div>

          <span class="divider spaced" aria-hidden="true"></span>

          <!-- 3. 백그라운드 자동 동기화 스위치 -->
          <div class="setting-row">
            <div class="setting-label">
              <span class="setting-name">백그라운드 자동 동기화</span>
              <span class="setting-hint">앱 실행 및 주기적으로 동기화를 수행합니다.</span>
            </div>
            <label class="switch">
              <input
                type="checkbox"
                aria-label="백그라운드 자동 동기화"
                checked={store.syncState.autoSyncEnabled}
                onchange={(event) => void store.toggleAutoSync(event.currentTarget.checked)}
              />
              <span class="switch-track" aria-hidden="true"></span>
            </label>
          </div>

          <span class="divider spaced" aria-hidden="true"></span>

          <!-- 4. 고급 동기화 설정 (마스터 복구 키) -->
          <button
            type="button"
            class="expander"
            aria-expanded={showAdvancedSync}
            onclick={() => (showAdvancedSync = !showAdvancedSync)}
          >
            <ChevronRight
              size={16}
              class={`expander-glyph${showAdvancedSync ? ' open' : ''}`}
              aria-hidden="true"
            />
            {showAdvancedSync
              ? '고급 동기화 설정 접기'
              : '고급 동기화 설정 (마스터 복구 키)'}
          </button>

          {#if showAdvancedSync}
            <div class="advanced-box">
              <div class="master-key-head">
                <span class="master-key-title">내 마스터 복구 키 (비상용)</span>
                {#if keyConfigured}
                  <button
                    type="button"
                    class="mini"
                    title="복사"
                    onclick={() => void copyToClipboard(store.syncState.syncKey)}
                  >
                    {#if copied}
                      <Check size={12} aria-hidden="true" /> 복사됨
                    {:else}
                      <Copy size={12} aria-hidden="true" /> 복사
                    {/if}
                  </button>
                {/if}
              </div>
              <code class="master-key-text">{masterKeyLabel}</code>
              <div class="master-key-footer">
                <button
                  type="button"
                  class="btn-text"
                  onclick={generateNewSyncKey}
                  disabled={busy !== null}
                >
                  새 마스터 키 재발급
                </button>
              </div>
            </div>
          {/if}
        </div>

        <!-- 섹션 2: 백업 및 복원 -->
        <div class="section-title">
          <Save size={18} aria-hidden="true" />
          <span>백업 및 복원</span>
        </div>

        <div class="setting-card">
          <p class="hint">
            기기 변경이나 앱 재설치에 대비해 만화 목록과 읽음 기록을 JSON 파일로 백업하고 복원합니다.
          </p>

          <input
            bind:this={backupFileInput}
            type="file"
            accept=".json"
            class="visually-hidden"
            onchange={handleBackupFile}
          />

          <div class="actions">
            <button type="button" class="btn tonal" onclick={exportBackup}>
              <Download size={16} aria-hidden="true" />
              <span>백업 파일 저장 (Export)</span>
            </button>
            <button type="button" class="btn outlined" onclick={triggerImportBackup}>
              <Upload size={16} aria-hidden="true" />
              <span>백업 파일 복원 (Import)</span>
            </button>
          </div>
        </div>
      {:else if category === 'NETWORK_DOWNLOAD'}
        <!-- ── NetworkSettingsPane.kt: WebDAV remote storage ────────────────────────────────── -->
        <div class="section-title">
          <Globe size={18} aria-hidden="true" />
          <span>네트워크 설정</span>
        </div>

        <div class="setting-card">
          <div class="card-head">
            <span class="head-glyph">
              <HardDrive size={18} aria-hidden="true" />
            </span>
            <span class="head-text">
              <span class="card-title">WebDAV 원격 스토리지 관리</span>
              <span class="card-desc">
                Synology · Nextcloud 등 WebDAV 서버의 만화 아카이브를 스트리밍으로 읽습니다.
              </span>
            </span>
            {#if webdavResult.kind === 'ok'}
              <span class="status-pill on"><span class="dot" aria-hidden="true"></span>연결 확인됨</span>
            {:else if webdavResult.kind === 'error'}
              <span class="status-pill error"><span class="dot" aria-hidden="true"></span>오류</span>
            {/if}
          </div>

          <div class="fields">
            <label class="field">
              <span class="field-label">서버 URL</span>
              <input
                type="url"
                bind:value={webdavUrl}
                placeholder="https://dav.example.com/comics"
                spellcheck="false"
                autocomplete="off"
              />
            </label>

            <div class="field-row">
              <label class="field">
                <span class="field-label">사용자 이름 (선택)</span>
                <input
                  type="text"
                  bind:value={webdavUsername}
                  autocomplete="username"
                  spellcheck="false"
                />
              </label>
              <label class="field">
                <span class="field-label">비밀번호 (선택)</span>
                <input type="password" bind:value={webdavPassword} autocomplete="current-password" />
              </label>
            </div>
          </div>

          <div class="actions">
            <button
              type="button"
              class="btn"
              onclick={testWebDav}
              disabled={webdavBusy || !webdavUrl.trim()}
            >
              {#if webdavBusy}
                <LoaderCircle size={14} class="spin" aria-hidden="true" /> 확인 중...
              {:else}
                <KeyRound size={14} aria-hidden="true" /> 연결 테스트
              {/if}
            </button>

            <button type="button" class="btn primary" onclick={openWebDavLibrary}>
              <HardDrive size={14} aria-hidden="true" /> WebDAV 라이브러리 열기
            </button>
          </div>

          {#if webdavResult.text}
            <p
              class="status-text"
              class:ok={webdavResult.kind === 'ok'}
              class:error={webdavResult.kind === 'error'}
              role="status"
            >
              {#if webdavResult.kind === 'error'}
                <CircleAlert size={13} aria-hidden="true" />
              {:else if webdavResult.kind === 'ok'}
                <Check size={13} aria-hidden="true" />
              {:else}
                <LoaderCircle size={13} aria-hidden="true" />
              {/if}
              {webdavResult.text}
            </p>
          {/if}
        </div>

        <!-- The proxy rows of `NetworkSettingsPane` (`strings.sectionNetwork`). -->
        <div class="section-title">
          <Globe size={18} aria-hidden="true" />
          <span>프록시</span>
        </div>

        <div class="setting-card">
          <div class="radio-group" role="radiogroup" aria-label="프록시 연결 방식">
            {#each [
              { type: 'DIRECT', label: '직접 연결', desc: '기기의 네트워크로 사이트에 직접 접속' },
              { type: 'SERVER', label: '서버 IP 사용', desc: '사이트 메타데이터 요청이 서버 IP를 경유' },
            ] as option (option.type)}
              {@const selected = store.networkSettings.proxyType === option.type}
              <button
                type="button"
                class="radio-option"
                class:selected
                role="radio"
                aria-checked={selected}
                data-proxy-type={option.type}
                onclick={() => store.setProxyType(option.type as 'DIRECT' | 'SERVER')}
              >
                <span class="radio-dot" aria-hidden="true"></span>
                <span class="radio-text">
                  <span class="radio-label">{option.label}</span>
                  <span class="radio-hint">{option.desc}</span>
                </span>
              </button>
              {#if option.type === 'DIRECT'}
                <span class="divider" aria-hidden="true"></span>
              {/if}
            {/each}
          </div>

          <p class="hint">
            브라우저 확장은 사용자 지정 HTTP/SOCKS 소켓을 열 수 없어 직접 연결과 서버 경유만
            제공합니다.
          </p>

          <span class="divider spaced" aria-hidden="true"></span>

          <div class="setting-row">
            <div class="setting-label">
              <span class="setting-name">연결 테스트</span>
              {#if store.proxyTestResult}
                <span
                  class="setting-hint"
                  class:ok={store.proxyTestSuccess === true}
                  class:error={store.proxyTestSuccess === false}
                >
                  {store.proxyTestResult}
                </span>
              {:else}
                <span class="setting-hint">선택한 경로로 접속이 가능한지 확인합니다.</span>
              {/if}
            </div>
            <button
              type="button"
              class="btn"
              onclick={() => void store.testProxyConnection()}
              disabled={store.proxyTesting}
            >
              {#if store.proxyTesting}
                <LoaderCircle size={14} class="spin" aria-hidden="true" /> 확인 중...
              {:else}
                테스트
              {/if}
            </button>
          </div>
        </div>
      {:else if category === 'ABOUT'}
        <!-- ── AboutSettingsPane.kt ─────────────────────────────────────────────────────────── -->
        <div class="section-title">
          <Info size={18} aria-hidden="true" />
          <span>앱 정보</span>
        </div>

        <div class="setting-card">
          <div class="setting-row">
            <div class="setting-label">
              <span class="setting-name">{APP_NAME}</span>
              <span class="setting-hint">버전 v{store.updateState.currentVersion}</span>
            </div>
            <div class="update-action-cell">
              <button
                type="button"
                class="button button-tonal"
                disabled={store.updateState.isChecking}
                onclick={checkUpdate}
              >
                {#if store.updateState.isChecking}
                  <LoaderCircle size={16} class="spin" aria-hidden="true" />
                  <span>확인 중...</span>
                {:else}
                  <RefreshCw size={16} aria-hidden="true" />
                  <span>업데이트 확인</span>
                {/if}
              </button>
            </div>
          </div>

          {#if updateFeedback}
            <p class="update-feedback" class:has-update={store.updateState.hasUpdate}>
              {updateFeedback}
            </p>
          {/if}

          {#if store.updateState.hasUpdate}
            <div class="update-alert-card">
              <div class="update-alert-header">
                <strong>새 버전 v{store.updateState.latestVersion} 사용 가능</strong>
              </div>
              {#if store.updateState.releaseNotes}
                <p class="update-notes">{store.updateState.releaseNotes}</p>
              {/if}
              <div class="update-buttons">
                <a
                  href={store.updateState.downloadUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  class="button button-primary"
                >
                  <ExternalLink size={16} aria-hidden="true" />
                  <span>다운로드 (GitHub Releases)</span>
                </a>
              </div>
            </div>
          {/if}

          <p class="hint">
            Comics8은 사이트 소스 플러그인, 로컬/WebDAV 라이브러리, 읽기 진행도 동기화를 하나로 묶은
            만화 뷰어입니다.
          </p>
        </div>

        {#if store.updateState.history.length > 0}
          <div class="section-title">
            <History size={18} aria-hidden="true" />
            <span>업데이트 내역</span>
          </div>

          <div class="history-list">
            {#each store.updateState.history as entry (entry.versionName + entry.date)}
              <div class="history-card setting-card">
                <div class="history-header">
                  <div class="history-version-row">
                    <span class="history-version">v{entry.versionName}</span>
                    {#if entry.versionName === store.updateState.currentVersion}
                      <span class="version-badge current">현재</span>
                    {/if}
                  </div>
                  {#if entry.date}
                    <span class="history-date">{entry.date}</span>
                  {/if}
                </div>
                {#if entry.releaseNotes}
                  <div class="history-notes">{entry.releaseNotes}</div>
                {/if}
              </div>
            {/each}
          </div>
        {/if}

        <div class="section-title">
          <Info size={18} aria-hidden="true" />
          <span>라이선스</span>
        </div>

        <div class="setting-card">
          <p class="license-name">MIT License</p>
          <p class="hint">Copyright (c) 2026 crudust</p>
          <p class="hint">
            소스 코드는 MIT 라이선스로 배포됩니다. 전문은 저장소의 LICENSE 파일에 있습니다.
          </p>
        </div>

        <div class="section-title">
          <HardDrive size={18} aria-hidden="true" />
          <span>캐시 관리</span>
        </div>

        <div class="setting-card">
          <div class="setting-row">
            <div class="setting-label">
              <span class="setting-name">이미지 / 목록 캐시</span>
              <span class="setting-hint">
                카탈로그 캐시와 이미지 비율 정보를 지웁니다. 다음 방문 시 다시 불러옵니다.
              </span>
            </div>
            <button type="button" class="btn danger" onclick={clearCache}>캐시 삭제</button>
          </div>

          {#if cacheNotice}
            <p class="status-text ok" role="status">
              <Check size={13} aria-hidden="true" />
              {cacheNotice}
            </p>
          {/if}
        </div>
      {/if}
    {/if}
  </div>

  {#if showPairingDialog}
    <SyncPairingDialog {store} onDismiss={() => (showPairingDialog = false)} />
  {/if}
  {#if showConnectDialog}
    <SyncConnectDialog {store} onDismiss={() => (showConnectDialog = false)} />
  {/if}
</section>

<style>
  /* --- frame: pure black surface, Zinc borders (the shell's MonochromeTheme) ------------------ */

  .pane {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
    background: #000000;
    color: #f4f4f5;
  }

  /* `TopAppBar(containerColor = colorScheme.background)`: 8dp vertical, 16dp horizontal. */
  .top-bar {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    background: #000000;
  }

  /* `Text(style = titleMedium, fontWeight = Bold)`. */
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

  .icon-button:hover {
    background: rgba(39, 39, 42, 0.85);
    color: #fafafa;
  }

  /* `LazyColumn(contentPadding = 16/8/16/24, spacedBy(14.dp))`. */
  .content {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 14px;
    min-height: 0;
    padding: 8px 16px 24px;
    overflow-y: auto;
  }

  /* --- SettingsCategoryCard --------------------------------------------------------------- */

  /* `Surface(shape = 14.dp, color = surfaceVariant, border = outlineVariant @ 0.35)` + hover. */
  .category-card {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 14px;
    width: 100%;
    padding: 16px;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 14px;
    background: #18181b;
    color: inherit;
    text-align: left;
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s;
  }

  .category-card:hover {
    border-color: rgba(113, 113, 122, 0.75);
    background: rgba(39, 39, 42, 0.85);
  }

  .category-card:focus-visible {
    outline: 2px solid #52525b;
    outline-offset: 2px;
  }

  /* `Box(42.dp, CircleShape, primaryContainer @ 0.7)`. */
  .category-glyph {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 42px;
    height: 42px;
    border-radius: 50%;
    background: #27272a;
    color: #fafafa;
  }

  .category-text {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .category-title {
    font-size: 15px;
    font-weight: 700;
    line-height: 1.3;
    color: #fafafa;
  }

  .category-summary {
    font-size: 12px;
    line-height: 1.35;
    color: #a1a1aa;
    display: -webkit-box;
    overflow: hidden;
    line-clamp: 2;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  :global(.category-chevron) {
    flex: 0 0 auto;
    color: rgba(161, 161, 170, 0.6);
  }

  /* --- SectionTitle / SettingCard ---------------------------------------------------------- */

  /* `SectionTitle`: 18dp icon, 6dp gap, labelLarge semibold, onSurfaceVariant. */
  .section-title {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 2px 4px;
    color: #a1a1aa;
    font-size: 12.5px;
    font-weight: 600;
  }

  /* `Surface(shape = 12.dp, color = surfaceVariant, border = outlineVariant @ 0.35)`. */
  .setting-card {
    display: flex;
    flex: 0 0 auto;
    flex-direction: column;
    gap: 10px;
    padding: 16px;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 12px;
    background: #18181b;
  }

  .setting-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .setting-label {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .setting-name {
    font-size: 13px;
    font-weight: 600;
    color: #f4f4f5;
  }

  .setting-hint {
    font-size: 11.5px;
    line-height: 1.4;
    color: #a1a1aa;
  }

  .setting-hint.ok {
    color: #6ee7b7;
  }

  .setting-hint.error {
    color: #fca5a5;
  }

  .divider {
    display: block;
    height: 1px;
    background: rgba(63, 63, 70, 0.35);
  }

  .divider.spaced {
    margin: 6px 0;
  }

  .group-label {
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    color: #f4f4f5;
  }

  .hint {
    margin: 0;
    font-size: 11px;
    line-height: 1.45;
    color: #71717a;
  }

  /* --- dropdown (`SettingDropdownRow`) ----------------------------------------------------- */

  .dropdown {
    position: relative;
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
  }

  .select {
    appearance: none;
    padding: 8px 30px 8px 12px;
    border: 1px solid rgba(63, 63, 70, 0.5);
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.7);
    color: #fafafa;
    font: 600 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .select:focus {
    outline: none;
    border-color: rgba(161, 161, 170, 0.8);
    box-shadow: 0 0 0 3px rgba(63, 63, 70, 0.5);
  }

  .select option {
    background: #18181b;
    color: #fafafa;
  }

  :global(.dropdown-glyph) {
    position: absolute;
    right: 9px;
    color: #a1a1aa;
    pointer-events: none;
  }

  /* --- chips (`ViewModeOption` / `FilterChip`) --------------------------------------------- */

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .chip {
    display: inline-flex;
    align-items: center;
    padding: 8px 12px;
    border: 1px solid rgba(63, 63, 70, 0.42);
    border-radius: 8px;
    background: #27272a;
    color: #d4d4d8;
    font: 500 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s, color 0.15s;
  }

  .chip:hover {
    background: #3f3f46;
    color: #fafafa;
  }

  .chip.selected {
    border-color: #fafafa;
    background: #fafafa;
    color: #000000;
    font-weight: 700;
  }

  .chip:focus-visible {
    outline: 2px solid #52525b;
    outline-offset: 2px;
  }

  /* --- radio rows (`NetworkSettingsPane`) -------------------------------------------------- */

  .radio-group {
    display: flex;
    flex-direction: column;
  }

  .radio-option {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 8px 4px;
    border: none;
    background: transparent;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }

  .radio-dot {
    flex: 0 0 auto;
    width: 16px;
    height: 16px;
    border: 2px solid #52525b;
    border-radius: 50%;
    background: transparent;
  }

  .radio-option.selected .radio-dot {
    border-color: #fafafa;
    background:
      radial-gradient(circle, #fafafa 0 4px, transparent 4px);
  }

  .radio-text {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
  }

  .radio-label {
    font-size: 12.5px;
    font-weight: 600;
    color: #f4f4f5;
  }

  .radio-option.selected .radio-label {
    color: #fafafa;
  }

  .radio-hint {
    font-size: 11px;
    line-height: 1.4;
    color: #71717a;
  }

  /* --- sync card --------------------------------------------------------------------------- */

  .sync-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
  }

  .sync-status {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }

  .status-pill {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    align-self: flex-start;
    padding: 3px 9px;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.04);
    color: #a1a1aa;
    font-size: 11px;
    font-weight: 700;
    white-space: nowrap;
  }

  .status-pill .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  .status-pill.on {
    border-color: rgba(52, 211, 153, 0.45);
    background: rgba(6, 78, 59, 0.35);
    color: #6ee7b7;
  }

  .status-pill.error {
    border-color: rgba(248, 113, 113, 0.45);
    background: rgba(127, 29, 29, 0.35);
    color: #fca5a5;
  }

  .sync-time {
    font-size: 11.5px;
    color: #71717a;
  }

  .inline-error {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0;
    padding: 8px 10px;
    border-radius: 7px;
    background: rgba(127, 29, 29, 0.3);
    color: #fecaca;
    font-size: 12px;
  }

  .pairing {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 12px;
    border: 1px dashed rgba(63, 63, 70, 0.6);
    border-radius: 10px;
    background: rgba(24, 24, 27, 0.45);
  }

  .pair-action-buttons {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 14px;
    margin-bottom: 6px;
  }

  .btn.full-width {
    width: 100%;
    justify-content: center;
  }

  .btn.tonal {
    background: #27272a;
    color: #f4f4f5;
    border: 1px solid rgba(63, 63, 70, 0.4);
  }

  .btn.tonal:hover {
    background: #3f3f46;
  }

  .btn.outlined {
    background: transparent;
    color: #e4e4e7;
    border: 1px solid rgba(113, 113, 122, 0.5);
  }

  .btn.outlined:hover {
    background: rgba(255, 255, 255, 0.05);
  }

  .advanced-box {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 10px 12px;
    margin-top: 10px;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.5);
  }

  .master-key-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .master-key-title {
    font-size: 11px;
    font-weight: 700;
    color: #a1a1aa;
  }

  .master-key-text {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
    font-weight: 700;
    color: #fafafa;
    word-break: break-all;
    margin: 2px 0;
  }

  .master-key-footer {
    display: flex;
    justify-content: flex-end;
  }

  .btn-text {
    background: transparent;
    border: none;
    color: #a1a1aa;
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
    padding: 2px 4px;
  }

  .btn-text:hover {
    color: #fafafa;
    text-decoration: underline;
  }

  .inline-notice {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 8px 0;
    padding: 8px 10px;
    border-radius: 6px;
    font-size: 12px;
  }

  .inline-notice.ok {
    background: rgba(34, 197, 94, 0.15);
    color: #4ade80;
    border: 1px solid rgba(34, 197, 94, 0.3);
  }

  .inline-notice.error {
    background: rgba(239, 68, 68, 0.15);
    color: #f87171;
    border: 1px solid rgba(239, 68, 68, 0.3);
  }

  .inline-notice.info {
    background: rgba(59, 130, 246, 0.15);
    color: #60a5fa;
    border: 1px solid rgba(59, 130, 246, 0.3);
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    border: 0;
  }

  .mini {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 2px 6px;
    border: 1px solid rgba(63, 63, 70, 0.7);
    border-radius: 5px;
    background: transparent;
    color: #a1a1aa;
    font-size: 10.5px;
    font-weight: 600;
    cursor: pointer;
  }

  .mini:hover {
    background: rgba(63, 63, 70, 0.6);
    color: #fafafa;
  }

  /* `Switch(checked = syncState.autoSyncEnabled)`. */
  .switch {
    position: relative;
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    cursor: pointer;
  }

  .switch input {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
  }

  .switch-track {
    position: relative;
    display: inline-block;
    width: 40px;
    height: 22px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 999px;
    background: #27272a;
    transition: background 0.15s, border-color 0.15s;
  }

  .switch-track::after {
    content: '';
    position: absolute;
    top: 3px;
    left: 3px;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    background: #a1a1aa;
    transition: transform 0.15s, background 0.15s;
  }

  .switch input:checked + .switch-track {
    border-color: #fafafa;
    background: #fafafa;
  }

  .switch input:checked + .switch-track::after {
    background: #000000;
    transform: translateX(18px);
  }

  .switch input:focus-visible + .switch-track {
    outline: 2px solid #52525b;
    outline-offset: 2px;
  }

  .expander {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 0;
    border: none;
    background: transparent;
    color: #a1a1aa;
    font: 600 11.5px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .expander:hover {
    color: #fafafa;
  }

  :global(.expander-glyph) {
    transition: transform 0.15s;
  }

  :global(.expander-glyph.open) {
    transform: rotate(90deg);
  }

  .advanced {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding-top: 2px;
  }

  /* --- notices ----------------------------------------------------------------------------- */

  .notice {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 9px 12px;
    border: 1px solid rgba(161, 161, 170, 0.35);
    border-radius: 8px;
    background: rgba(39, 39, 42, 0.6);
    color: #e4e4e7;
    font-size: 12.5px;
    line-height: 1.4;
  }

  .notice.ok {
    border-color: rgba(52, 211, 153, 0.35);
    background: rgba(6, 78, 59, 0.32);
    color: #a7f3d0;
  }

  .notice.error {
    border-color: rgba(248, 113, 113, 0.4);
    background: rgba(127, 29, 29, 0.35);
    color: #fecaca;
  }

  .notice-close {
    display: inline-flex;
    align-items: center;
    margin-left: auto;
    padding: 2px;
    border: 0;
    background: transparent;
    color: inherit;
    cursor: pointer;
    opacity: 0.7;
  }

  .notice-close:hover {
    opacity: 1;
  }

  /* --- buttons ----------------------------------------------------------------------------- */

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 7px 12px;
    border: 1px solid rgba(63, 63, 70, 0.7);
    border-radius: 8px;
    background: rgba(39, 39, 42, 0.85);
    color: #e4e4e7;
    font: 600 12px/1.2 var(--font-sans, system-ui, sans-serif);
    white-space: nowrap;
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s, color 0.15s;
  }

  .btn:hover:not(:disabled) {
    border-color: rgba(161, 161, 170, 0.7);
    background: #3f3f46;
    color: #ffffff;
  }

  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .btn.primary {
    border-color: #fafafa;
    background: #fafafa;
    color: #000000;
    font-weight: 700;
  }

  .btn.primary:hover:not(:disabled) {
    background: #e4e4e7;
    border-color: #e4e4e7;
    color: #000000;
  }

  .btn.ghost {
    background: transparent;
  }

  .btn.small {
    padding: 5px 9px;
    font-size: 11px;
  }

  .btn.danger {
    border-color: rgba(248, 113, 113, 0.45);
    background: rgba(127, 29, 29, 0.35);
    color: #fecaca;
  }

  .btn.danger:hover:not(:disabled) {
    border-color: rgba(248, 113, 113, 0.8);
    background: rgba(153, 27, 27, 0.6);
    color: #ffffff;
  }

  /* --- webdav card ------------------------------------------------------------------------- */

  .card-head {
    display: flex;
    align-items: flex-start;
    gap: 10px;
  }

  .head-glyph {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: 1px solid rgba(63, 63, 70, 0.7);
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.03);
    color: #a1a1aa;
  }

  .head-text {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }

  .card-title {
    font-size: 13px;
    font-weight: 700;
    color: #fafafa;
  }

  .card-desc {
    font-size: 11.5px;
    line-height: 1.45;
    color: #a1a1aa;
  }

  .fields {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
  }

  .field-label {
    font-size: 11px;
    font-weight: 700;
    color: #a1a1aa;
  }

  .field input {
    padding: 8px 10px;
    border: 1px solid rgba(63, 63, 70, 0.7);
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.45);
    color: #f4f4f5;
    font: 500 12.5px/1.3 var(--font-sans, system-ui, sans-serif);
  }

  .field input::placeholder {
    color: #52525b;
  }

  .field input:focus {
    outline: none;
    border-color: rgba(250, 250, 250, 0.6);
    box-shadow: 0 0 0 3px rgba(63, 63, 70, 0.5);
  }

  .field-row {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 10px;
  }

  .status-text {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0;
    font-size: 12px;
    line-height: 1.4;
    color: #a1a1aa;
  }

  .status-text.ok {
    color: #6ee7b7;
  }

  .status-text.error {
    color: #fca5a5;
  }

  /* --- about ------------------------------------------------------------------------------- */

  .version-badge {
    flex: 0 0 auto;
    padding: 2px 7px;
    border-radius: 6px;
    background: #27272a;
    color: #fafafa;
    font-size: 10.5px;
    font-weight: 600;
  }

  .license-name {
    margin: 0;
    font-size: 13px;
    font-weight: 700;
    color: #fafafa;
  }

  :global(.spin) {
    animation: settings-spin 0.9s linear infinite;
  }

  @keyframes settings-spin {
    to {
      transform: rotate(360deg);
    }
  }

  .update-action-cell {
    display: flex;
    align-items: center;
  }

  .update-feedback {
    margin: 8px 0 0;
    font-size: 12px;
    color: #a1a1aa;
  }

  .update-feedback.has-update {
    color: #38bdf8;
    font-weight: 600;
  }

  .update-alert-card {
    margin-top: 10px;
    padding: 12px;
    border-radius: 8px;
    background: rgba(56, 189, 248, 0.08);
    border: 1px solid rgba(56, 189, 248, 0.3);
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .update-alert-header {
    font-size: 13px;
    color: #38bdf8;
  }

  .update-notes {
    font-size: 11.5px;
    line-height: 1.45;
    color: #d4d4d8;
    white-space: pre-wrap;
    margin: 0;
  }

  .update-buttons {
    display: flex;
    gap: 8px;
    margin-top: 4px;
  }

  .history-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .history-card {
    padding: 12px;
  }

  .history-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }

  .history-version-row {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .history-version {
    font-size: 13px;
    font-weight: 700;
    color: #fafafa;
  }

  .version-badge.current {
    background: rgba(34, 197, 94, 0.15);
    color: #4ade80;
    border: 1px solid rgba(34, 197, 94, 0.3);
  }

  .history-date {
    font-size: 11px;
    color: #71717a;
  }

  .history-notes {
    font-size: 11.5px;
    line-height: 1.45;
    color: #a1a1aa;
    white-space: pre-wrap;
  }

  @media (max-width: 520px) {
    .content {
      padding: 8px 10px 20px;
    }

    .pin-form {
      flex-direction: column;
    }
  }
</style>
