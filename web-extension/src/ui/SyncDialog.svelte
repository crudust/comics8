<script lang="ts">
  /**
   * Cloud-sync modal, a 1:1 port of `desktop/src/main/kotlin/ui/components/SyncDialog.kt`
   * (lines 64-617): ONE `AlertDialog` plus TWO nested dialogs.
   *
   * Kotlin contract, mirrored literally below:
   * - `SyncDialog` (`AlertDialog`): title row `Icons.Default.CloudSync` + `strings.titleCloudSync`;
   *   body = `descCloudSync`, a `FilledTonalButton` (`Devices`, `actionOpenPairingDialog`) that opens
   *   the pairing dialog, an `OutlinedButton` (`Key`, `actionOpenConnectDialogShort`) that opens the
   *   connect dialog, a `HorizontalDivider`, the `labelAutoSync` / `descAutoSync` switch row
   *   (`viewModel.toggleAutoSync`), the fold row
   *   (`labelUnfoldAdvancedSync` / `labelFoldAdvancedSync` → `ExpandMore` / `ExpandLess`) that reveals
   *   the master-key card (`labelMyMasterKey`, copy, `syncState.syncKey` in monospace/primary,
   *   `actionReissueMasterKey` → `viewModel.generateNewSyncKey`), and the
   *   `labelLastSyncedAt("yyyy-MM-dd HH:mm:ss")` / `labelNoSyncHistory` line;
   *   confirm = `actionSyncNow` (`Refresh` + `statusSyncing` while `syncState.isSyncing`), dismiss =
   *   `actionClose`.
   * - `DesktopSyncDialogPairingCode`: opens with `viewModel.requestPairingCode`, prints the code as
   *   `123 456` (`headlineLarge`, monospace, primary) with a copy action, counts
   *   `expiresInSeconds` (300, `PAIR_CODE_TTL_SECONDS`) down as
   *   `labelPairingTimeLeft` → `errorPairingCodeExpired`, confirm = `actionIssueNewCode`, dismiss =
   *   `actionClose`.
   * - `DesktopSyncDialogConnectDevice`: `placeholder6DigitCode` / `placeholderMasterKey` inputs, a
   *   text-button toggle (`actionSwitchToMasterKeyMode` / `actionSwitchToPairingCodeMode`), one
   *   status banner (error container vs primary container), confirm = `actionImportData`
   *   (`statusConnecting` + spinner while in flight) calling `confirmPairingCode` or
   *   `restoreWithMasterKey`, dismiss = `actionCancel`.
   *
   * Port mapping notes:
   * - Kotlin takes `state` + `viewModel`; this component takes one {@link DesktopStore} (the same
   *   collapse `TopBar.svelte` / `BrowsePane.svelte` use), defaulting to the shell's
   *   `desktopState`. Every call below is the store method of the same name as its
   *   `DesktopViewModel` counterpart, so no sync logic is duplicated in the view:
   *   `syncNow` / `toggleAutoSync` / `generateNewSyncKey` / `requestPairingCode` /
   *   `confirmPairingCode` / `updateSyncKey` (= `DesktopViewModel.updateSyncKey`, the web twin of
   *   `restoreWithMasterKey`).
   * - `restoreWithMasterKey` imports the account into the platform store layer. The browser stores
   *   (`data/favorites-store`, `data/history-store`) expose no bulk writer, so the web twin installs
   *   the key (`updateSyncKey`) and closes, exactly like `SettingsPane.svelte` documents for
   *   backup restore; the next sync pulls the counters back through `mergeRemoteHistory`.
   * - Two intentional, documented deviations from the Kotlin layout:
   *   1. the main dialog shows an error banner when *this* dialog's sync attempt fails — the desktop
   *      leaves `syncMessage` to the settings pane, but this modal is opened from the top bar with no
   *      other visible surface reporting the failure;
   *   2. the pairing timer drives off an absolute expiry timestamp instead of decrementing a counter,
   *      so a throttled background tab still reports the true remaining time (same visible
   *      behaviour: one second less per second, then the expired wording).
   *
   * Icon mapping (lucide-svelte has no Material icon set): `CloudSync` → `CloudSync`, `Devices` →
   * `MonitorSmartphone` (the closest "several device shapes" glyph), `Key` → `Key`, `ExpandMore` /
   * `ExpandLess` → `ChevronDown` / `ChevronUp`, `ContentCopy` → `Copy`, `Refresh` → `RefreshCw`, and
   * the `CircularProgressIndicator`s → `LoaderCircle` / a CSS spinner.
   */

  import { onMount } from 'svelte';
  import {
    ChevronDown,
    ChevronUp,
    CloudSync,
    Copy,
    Key,
    LoaderCircle,
    MonitorSmartphone,
    RefreshCw,
  } from 'lucide-svelte';

  import { describeError } from '../messaging';
  import { desktopState, type DesktopStore } from '../state/desktop-state.svelte';
  import { formatCountdown } from '../views/format';

  interface Props {
    /** Dismiss without changing anything (backdrop, Escape, `닫기`). */
    onDismiss?: () => void;
    /** Store to render and drive; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
  }

  let { onDismiss, store = desktopState }: Props = $props();

  // `KoStrings` values, inlined so the labels cannot drift from the Kotlin contract.
  // Where the port request paraphrased a label, the shipped `core/src/main/kotlin/i18n/KoStrings.kt`
  // string wins (it is what the Kotlin dialog actually renders):
  //   actionOpenPairingDialog     = "새 기기 연결하기 (6자리 코드 발급)"
  //   actionOpenConnectDialogShort= "다른 기기의 데이터 가져오기"
  //   titlePairNewDevice          = "새 기기 연결 (페어링)"
  //   titleImportExistingData     = "기존 데이터 가져오기"
  const TITLE = '클라우드 자동 동기화';
  const DESC =
    '개인정보 없이 6자리 페어링 코드로 스마트폰(Android), 태블릿 등 다른 기기와 즐겨찾기 및 읽음 진행도를 실시간 동기화합니다.';
  const ACTION_OPEN_PAIRING = '새 기기 연결하기 (6자리 코드 발급)';
  const ACTION_OPEN_CONNECT = '다른 기기의 데이터 가져오기';
  const LABEL_AUTO_SYNC = '백그라운드 자동 동기화';
  const DESC_AUTO_SYNC = '만화 열람/즐겨찾기 변경 시 서버에 실시간 자동 동기화';
  const LABEL_FOLD_ADVANCED = '고급 동기화 설정 접기';
  const LABEL_UNFOLD_ADVANCED = '고급 동기화 설정 (마스터 복구 키)';
  const LABEL_MY_MASTER_KEY = '내 마스터 복구 키 (비상용)';
  const STATUS_ISSUING_KEY = '키 발급 중...';
  const ACTION_REISSUE = '새 마스터 키 재발급';
  const ACTION_COPY = '복사';
  const ACTION_SYNC_NOW = '지금 즉시 동기화';
  const STATUS_SYNCING = '동기화 중...';
  const ACTION_CLOSE = '닫기';
  const ACTION_CANCEL = '취소';
  const LABEL_NO_SYNC_HISTORY = '동기화 이력 없음';

  // `DesktopSyncDialogPairingCode`
  const TITLE_PAIR_NEW_DEVICE = '새 기기 연결 (페어링)';
  const HINT_PAIRING_GUIDE =
    '새로 연결할 기기(스마트폰, 태블릿, PC 등)의 [데이터 가져오기] 화면에서 아래 6자리 코드를 입력하세요.';
  const ERROR_PAIRING_EXPIRED = '⚠️ 코드가 만료되었습니다.';
  const MSG_PAIRING_ISSUE_FAILED = '페어링 코드 발급 실패';
  const ACTION_ISSUE_NEW_CODE = '새 코드 발급';

  // `DesktopSyncDialogConnectDevice`
  const TITLE_IMPORT_EXISTING = '기존 데이터 가져오기';
  const TITLE_CONNECT_WITH_KEY = '마스터 복구 키로 연결';
  const HINT_INPUT_CODE =
    '기존에 사용하던 기기의 [새 기기 연결하기]에 표시된 6자리 번호를 입력하세요.';
  const HINT_MASTER_KEY =
    '이전에 저장해 둔 마스터 복구 키(C8-XXXX-XXXX-XXXX)를 입력하여 데이터를 직접 복원합니다.';
  const PLACEHOLDER_CODE = '예: 849201';
  const PLACEHOLDER_KEY = '예: C8-7X9K-2M4Q-99PL';
  const ACTION_SWITCH_TO_KEY = '기존 기기가 없나요? 마스터 복구 키로 복원';
  const ACTION_SWITCH_TO_CODE = '6자리 페어링 코드로 입력하기';
  const STATUS_CONNECTING = '연결 중...';
  const ACTION_IMPORT_DATA = '데이터 가져오기';
  const MSG_DATA_IMPORT_FAILED = '데이터 가져오기 실패';
  const MSG_INVALID_MASTER_KEY = '올바른 마스터 복구 키 형식이 아닙니다.';

  const CODE_LENGTH = 6;

  // --- main dialog ------------------------------------------------------------------------------

  let showAdvancedSettings = $state(false);
  let showPairingDialog = $state(false);
  let showConnectDialog = $state(false);
  /** Failure of *this* dialog's `actionSyncNow` press (see deviation #1 above). */
  let syncFailure = $state<string | null>(null);
  let dialogEl = $state<HTMLDivElement | undefined>();

  /** `strings.labelLastSyncedAt(SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))` / `labelNoSyncHistory`. */
  const lastSyncLabel: string = $derived.by(() => {
    const timestamp = store.syncState.lastSyncedAt;
    return timestamp > 0
      ? `마지막 동기화: ${formatAbsoluteTime(timestamp)}`
      : LABEL_NO_SYNC_HISTORY;
  });

  /** `syncState.syncKey.ifBlank { strings.statusIssuingKey }`. */
  const masterKeyLabel: string = $derived(store.syncState.syncKey.trim() || STATUS_ISSUING_KEY);

  /** `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())`. */
  function formatAbsoluteTime(timestamp: number): string {
    const date = new Date(timestamp);
    const pad = (value: number): string => `${value}`.padStart(2, '0');
    return (
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    );
  }

  onMount(() => {
    // Focus moves into the modal so Escape and the fields belong to the dialog rather than to
    // whatever was focused behind it. Hydrating is idempotent (`SyncClient.init`).
    dialogEl?.focus();
    void store.loadSyncState();
  });

  function dismiss(): void {
    onDismiss?.();
  }

  /**
   * Keyboard twin of a backdrop/dialog click, for events that did not go through the window capture
   * handler (the element had focus and nothing consumed the key yet). `defaultPrevented` guards
   * against acting twice on the same Escape.
   */
  function onEscapeKeydown(event: KeyboardEvent, close: () => void): void {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    event.preventDefault();
    event.stopPropagation();
    close();
  }

  /** Escape closes the top-most dialog only, and never leaks to the reader behind the modal. */
  function onWindowKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    event.preventDefault();
    event.stopPropagation();
    if (showConnectDialog) {
      closeConnectDialog();
      return;
    }
    if (showPairingDialog) {
      closePairingDialog();
      return;
    }
    dismiss();
  }

  /** `confirmButton` of the main dialog: `viewModel.syncNow()`. */
  async function syncNow(): Promise<void> {
    if (store.syncState.isSyncing) return;
    syncFailure = null;
    const result = await store.syncNow();
    if (!result.ok) syncFailure = result.message;
  }

  /** `Switch(onCheckedChange = { viewModel.toggleAutoSync(it) })`. */
  async function toggleAutoSync(event: Event): Promise<void> {
    const enabled = (event.currentTarget as HTMLInputElement).checked;
    try {
      await store.toggleAutoSync(enabled);
    } catch (error) {
      syncFailure = describeError(error);
    }
  }

  /** `actionReissueMasterKey` → `viewModel.generateNewSyncKey()`. */
  async function reissueMasterKey(): Promise<void> {
    try {
      await store.generateNewSyncKey();
    } catch (error) {
      syncFailure = describeError(error);
    }
  }

  async function copyText(value: string): Promise<void> {
    if (value.trim() === '') return;
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      // Kotlin swallows a clipboard failure (`catch (_: Exception) {}`); so does this.
    }
  }

  // --- DesktopSyncDialogPairingCode -------------------------------------------------------------

  let pairingLoading = $state(false);
  let pairingCode = $state('');
  let pairingError = $state<string | null>(null);
  let pairingExpiresAt = $state(0);
  let secondsLeft = $state(0);

  /** `if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code`. */
  const formattedCode: string = $derived(
    pairingCode.length === CODE_LENGTH
      ? `${pairingCode.slice(0, 3)} ${pairingCode.slice(3)}`
      : pairingCode,
  );

  /** `strings.labelPairingTimeLeft(min, sec)` → `⏱️ 남은 시간: mm:ss`.
   * `formatCountdown` is the same `mm:ss` helper `SettingsPane.svelte` uses. */
  const pairingTimerText: string = $derived(
    secondsLeft > 0
      ? `⏱️ 남은 시간: ${formatCountdown(secondsLeft)}`
      : ERROR_PAIRING_EXPIRED,
  );

  /** `showPairingDialog = true` — the Compose dialog mounts fresh, so its state is reset here. */
  function openPairingDialog(): void {
    showPairingDialog = true;
    pairingCode = '';
    pairingError = null;
    pairingExpiresAt = 0;
    secondsLeft = 0;
    void loadPairingCode();
  }

  function closePairingDialog(): void {
    showPairingDialog = false;
    pairingCode = '';
    pairingError = null;
    pairingExpiresAt = 0;
    secondsLeft = 0;
  }

  /** `LaunchedEffect(Unit) { loadCode() }` / the `actionIssueNewCode` button. */
  async function loadPairingCode(): Promise<void> {
    pairingLoading = true;
    pairingError = null;
    pairingCode = '';
    pairingExpiresAt = 0;
    secondsLeft = 0;
    try {
      const result = await store.requestPairingCode();
      pairingCode = result.code;
      pairingExpiresAt = Date.now() + result.expiresInSeconds * 1000;
      secondsLeft = result.expiresInSeconds;
    } catch (error) {
      pairingError = describeError(error) || MSG_PAIRING_ISSUE_FAILED;
    } finally {
      pairingLoading = false;
    }
  }

  // The countdown is driven by the expiry timestamp (deviation #2), so a tick that lands late
  // still reports the remaining time instead of drifting like a decremented counter would.
  $effect(() => {
    const expiresAt = pairingExpiresAt;
    if (!expiresAt) return;

    const tick = (): void => {
      const left = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
      secondsLeft = left;
      if (left === 0) pairingExpiresAt = 0;
    };

    tick();
    const timer = setInterval(tick, 250);
    return () => clearInterval(timer);
  });

  // --- DesktopSyncDialogConnectDevice -----------------------------------------------------------

  let pairingCodeInput = $state('');
  let recoveryKeyInput = $state('');
  let useRecoveryKeyMode = $state(false);
  let isConnecting = $state(false);
  let statusMessage = $state<string | null>(null);
  let isError = $state(false);

  const connectTitle: string = $derived(
    useRecoveryKeyMode ? TITLE_CONNECT_WITH_KEY : TITLE_IMPORT_EXISTING,
  );
  const canImport: boolean = $derived(
    !isConnecting &&
      (useRecoveryKeyMode
        ? recoveryKeyInput.trim() !== ''
        : pairingCodeInput.length === CODE_LENGTH),
  );

  /** `showConnectDialog = true` — fresh state, like a newly composed `AlertDialog`. */
  function openConnectDialog(): void {
    showConnectDialog = true;
    pairingCodeInput = '';
    recoveryKeyInput = '';
    useRecoveryKeyMode = false;
    isConnecting = false;
    statusMessage = null;
    isError = false;
  }

  function closeConnectDialog(): void {
    showConnectDialog = false;
    pairingCodeInput = '';
    recoveryKeyInput = '';
    useRecoveryKeyMode = false;
    isConnecting = false;
    statusMessage = null;
    isError = false;
  }

  /** `onValueChange = { it.filter { ch -> ch.isDigit() }.take(6) }`. */
  function onCodeInput(event: Event): void {
    const input = event.currentTarget as HTMLInputElement;
    pairingCodeInput = input.value.replace(/\D/g, '').slice(0, CODE_LENGTH);
    input.value = pairingCodeInput;
  }

  /** `onValueChange = { it.trim().uppercase() }`. */
  function onKeyInput(event: Event): void {
    const input = event.currentTarget as HTMLInputElement;
    recoveryKeyInput = input.value.trim().toUpperCase();
    input.value = recoveryKeyInput;
  }

  function switchMode(toRecoveryKey: boolean): void {
    useRecoveryKeyMode = toRecoveryKey;
    statusMessage = null;
    isError = false;
  }

  /** The connect `confirmButton`: `confirmPairingCode` or `restoreWithMasterKey`, then dismiss. */
  async function importData(): Promise<void> {
    if (!canImport) return;
    isConnecting = true;
    statusMessage = null;
    isError = false;
    try {
      if (!useRecoveryKeyMode) {
        await store.confirmPairingCode(pairingCodeInput);
        closeConnectDialog();
      } else {
        const key = recoveryKeyInput.trim();
        if (key === '') {
          isError = true;
          statusMessage = MSG_INVALID_MASTER_KEY;
          return;
        }
        await store.updateSyncKey(key);
        closeConnectDialog();
      }
    } catch (error) {
      isError = true;
      statusMessage = describeError(error) || MSG_DATA_IMPORT_FAILED;
    } finally {
      isConnecting = false;
    }
  }
</script>

<svelte:window onkeydowncapture={onWindowKeydown} />

<!-- ── SyncDialog ─────────────────────────────────────────────────────────────────────────────── -->
<div
  class="dialog-backdrop"
  role="presentation"
  onclick={dismiss}
  onkeydown={(event) => onEscapeKeydown(event, dismiss)}
>
  <div
    class="dialog sync-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="sync-dialog-title"
    tabindex="-1"
    data-testid="sync-dialog"
    bind:this={dialogEl}
    onclick={(event) => event.stopPropagation()}
    onkeydown={(event) => onEscapeKeydown(event, dismiss)}
  >
    <header class="dialog-header">
      <CloudSync size={24} class="title-icon" aria-hidden="true" />
      <h2 class="dialog-title" id="sync-dialog-title">{TITLE}</h2>
    </header>

    <div class="dialog-body">
      <p class="desc">{DESC}</p>

      <!-- 1. 기기 페어링 액션 버튼들 (`FilledTonalButton` / `OutlinedButton`) -->
      <button type="button" class="action filled-tonal open-pairing" onclick={openPairingDialog}>
        <MonitorSmartphone size={18} aria-hidden="true" />
        <span>{ACTION_OPEN_PAIRING}</span>
      </button>

      <button type="button" class="action outlined open-connect" onclick={openConnectDialog}>
        <Key size={18} aria-hidden="true" />
        <span>{ACTION_OPEN_CONNECT}</span>
      </button>

      <hr class="divider" />

      <!-- 2. 백그라운드 동기화 스위치 -->
      <div class="auto-sync-row">
        <div class="auto-sync-text">
          <span class="row-title">{LABEL_AUTO_SYNC}</span>
          <span class="row-desc">{DESC_AUTO_SYNC}</span>
        </div>
        <label class="switch">
          <input
            type="checkbox"
            aria-label={LABEL_AUTO_SYNC}
            checked={store.syncState.autoSyncEnabled}
            onchange={toggleAutoSync}
          />
          <span class="switch-track" aria-hidden="true"></span>
        </label>
      </div>

      <!-- 3. 고급 동기화 설정 (접이식) -->
      <button
        type="button"
        class="fold-row"
        aria-expanded={showAdvancedSettings}
        onclick={() => (showAdvancedSettings = !showAdvancedSettings)}
      >
        {#if showAdvancedSettings}
          <ChevronUp size={16} aria-hidden="true" />
          <span>{LABEL_FOLD_ADVANCED}</span>
        {:else}
          <ChevronDown size={16} aria-hidden="true" />
          <span>{LABEL_UNFOLD_ADVANCED}</span>
        {/if}
      </button>

      {#if showAdvancedSettings}
        <div class="advanced-card">
          <div class="advanced-head">
            <span class="advanced-label">{LABEL_MY_MASTER_KEY}</span>
            <button
              type="button"
              class="icon-button copy-key"
              title={ACTION_COPY}
              aria-label={ACTION_COPY}
              onclick={() => void copyText(store.syncState.syncKey)}
            >
              <Copy size={14} aria-hidden="true" />
            </button>
          </div>
          <code class="advanced-key">{masterKeyLabel}</code>
          <div class="advanced-actions">
            <button type="button" class="text-button reissue" onclick={() => void reissueMasterKey()}>
              {ACTION_REISSUE}
            </button>
          </div>
        </div>
      {/if}

      <!-- 4. 마지막 동기화 시간 -->
      <p class="last-synced">{lastSyncLabel}</p>

      {#if syncFailure}
        <div class="banner error" role="alert">{syncFailure}</div>
      {/if}
    </div>

    <footer class="dialog-actions">
      <button type="button" class="action outlined close" onclick={dismiss}>{ACTION_CLOSE}</button>
      <button
        type="button"
        class="action primary sync-now"
        disabled={store.syncState.isSyncing}
        onclick={() => void syncNow()}
      >
        {#if store.syncState.isSyncing}
          <LoaderCircle size={16} class="spin" aria-hidden="true" />
          <span>{STATUS_SYNCING}</span>
        {:else}
          <RefreshCw size={16} aria-hidden="true" />
          <span>{ACTION_SYNC_NOW}</span>
        {/if}
      </button>
    </footer>
  </div>
</div>

<!-- ── DesktopSyncDialogPairingCode ──────────────────────────────────────────────────────────── -->
{#if showPairingDialog}
  <div
    class="dialog-backdrop sub"
    role="presentation"
    onclick={closePairingDialog}
    onkeydown={(event) => onEscapeKeydown(event, closePairingDialog)}
  >
    <div
      class="dialog pairing-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="sync-pairing-title"
      tabindex="-1"
      data-testid="sync-pairing-dialog"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => onEscapeKeydown(event, closePairingDialog)}
    >
      <header class="dialog-header">
        <MonitorSmartphone size={24} class="title-icon" aria-hidden="true" />
        <h2 class="dialog-title" id="sync-pairing-title">{TITLE_PAIR_NEW_DEVICE}</h2>
      </header>

      <div class="dialog-body centered">
        <p class="desc">{HINT_PAIRING_GUIDE}</p>

        {#if pairingLoading}
          <div class="pairing-loading">
            <span class="spinner" role="status" aria-label="페어링 코드 발급 중"></span>
          </div>
        {:else if pairingError}
          <div class="banner error pairing-error" role="alert">{pairingError}</div>
        {:else}
          <div class="code-card">
            <span class="code">{formattedCode}</span>
            <span class="code-timer" class:expired={secondsLeft === 0}>{pairingTimerText}</span>
          </div>

          <!-- `TextButton { Icon(ContentCopy, 16.dp); Text(actionCopy) }`. -->
          <button type="button" class="text-button copy-code" onclick={() => void copyText(pairingCode)}>
            <Copy size={16} aria-hidden="true" />
            <span>{ACTION_COPY}</span>
          </button>
        {/if}
      </div>

      <footer class="dialog-actions">
        <button type="button" class="action outlined" onclick={closePairingDialog}>
          {ACTION_CLOSE}
        </button>
        <button
          type="button"
          class="action primary issue-new-code"
          disabled={pairingLoading}
          onclick={() => void loadPairingCode()}
        >
          <RefreshCw size={16} aria-hidden="true" />
          <span>{ACTION_ISSUE_NEW_CODE}</span>
        </button>
      </footer>
    </div>
  </div>
{/if}

<!-- ── DesktopSyncDialogConnectDevice ────────────────────────────────────────────────────────── -->
{#if showConnectDialog}
  <div
    class="dialog-backdrop sub"
    role="presentation"
    onclick={closeConnectDialog}
    onkeydown={(event) => onEscapeKeydown(event, closeConnectDialog)}
  >
    <div
      class="dialog connect-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="sync-connect-title"
      tabindex="-1"
      data-testid="sync-connect-dialog"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => onEscapeKeydown(event, closeConnectDialog)}
    >
      <header class="dialog-header">
        <CloudSync size={24} class="title-icon" aria-hidden="true" />
        <h2 class="dialog-title" id="sync-connect-title">{connectTitle}</h2>
      </header>

      <div class="dialog-body">
        {#if !useRecoveryKeyMode}
          <p class="desc">{HINT_INPUT_CODE}</p>
          <input
            class="outlined-field code-field"
            type="text"
            inputmode="numeric"
            autocomplete="one-time-code"
            maxlength={CODE_LENGTH}
            placeholder={PLACEHOLDER_CODE}
            aria-label="6자리 페어링 코드"
            value={pairingCodeInput}
            oninput={onCodeInput}
          />
          <div class="mode-row">
            <button
              type="button"
              class="text-button switch-mode"
              onclick={() => switchMode(true)}
            >
              {ACTION_SWITCH_TO_KEY}
            </button>
          </div>
        {:else}
          <p class="desc">{HINT_MASTER_KEY}</p>
          <input
            class="outlined-field key-field"
            type="text"
            autocomplete="off"
            spellcheck="false"
            placeholder={PLACEHOLDER_KEY}
            aria-label="마스터 복구 키"
            value={recoveryKeyInput}
            oninput={onKeyInput}
          />
          <div class="mode-row">
            <button
              type="button"
              class="text-button switch-mode"
              onclick={() => switchMode(false)}
            >
              {ACTION_SWITCH_TO_CODE}
            </button>
          </div>
        {/if}

        {#if statusMessage}
          <div
            class="banner"
            class:error={isError}
            class:info={!isError}
            role={isError ? 'alert' : 'status'}
          >
            {statusMessage}
          </div>
        {/if}
      </div>

      <footer class="dialog-actions">
        <button type="button" class="action outlined" onclick={closeConnectDialog}>
          {ACTION_CANCEL}
        </button>
        <button
          type="button"
          class="action primary import-data"
          disabled={!canImport}
          onclick={() => void importData()}
        >
          {#if isConnecting}
            <LoaderCircle size={16} class="spin" aria-hidden="true" />
            <span>{STATUS_CONNECTING}</span>
          {:else}
            <span>{ACTION_IMPORT_DATA}</span>
          {/if}
        </button>
      </footer>
    </div>
  </div>
{/if}

<style>
  /* Kotlin `AlertDialog` scrim. */
  .dialog-backdrop {
    position: fixed;
    inset: 0;
    z-index: 120;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    background: rgba(0, 0, 0, 0.72);
    backdrop-filter: blur(2px);
  }

  /* The two nested `AlertDialog`s stack over the main one (Kotlin composes them on top). */
  .dialog-backdrop.sub {
    z-index: 130;
  }

  .dialog {
    display: flex;
    flex-direction: column;
    width: min(440px, 100%);
    max-height: min(680px, 100%);
    border: 1px solid #27272a;
    border-radius: 16px;
    background: #18181b;
    color: #fafafa;
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.6);
    overflow: hidden;
  }

  .dialog-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 18px 20px 0;
    min-width: 0;
  }

  /* `Icon(tint = MaterialTheme.colorScheme.primary)`; `:global` because the class lands on the
     lucide component, not on a DOM element here. */
  .dialog-header :global(.title-icon) {
    flex: 0 0 auto;
    color: #60a5fa;
  }

  .dialog-title {
    margin: 0;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: -0.01em;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .dialog-body {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 12px;
    min-height: 0;
    padding: 14px 20px 8px;
    overflow-y: auto;
  }

  .dialog-body.centered {
    align-items: center;
  }

  .desc {
    margin: 0;
    font-size: 11.5px;
    line-height: 1.55;
    color: #a1a1aa;
  }

  /* --- action buttons (`FilledTonalButton` / `OutlinedButton` / `Button`) --------------------- */

  .action {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    padding: 10px 18px;
    border-radius: 10px;
    font: 600 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    white-space: nowrap;
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s, color 0.15s;
  }

  .action:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }

  .action.filled-tonal,
  .action.outlined {
    width: 100%;
  }

  /* `FilledTonalButton`: secondary container, primary-tinted label. */
  .action.filled-tonal {
    border: 1px solid rgba(59, 130, 246, 0.35);
    background: rgba(59, 130, 246, 0.16);
    color: #93c5fd;
  }

  .action.filled-tonal:hover {
    background: rgba(59, 130, 246, 0.26);
  }

  .action.outlined {
    border: 1px solid #3f3f46;
    background: transparent;
    color: #e4e4e7;
  }

  .action.outlined:hover {
    background: rgba(63, 63, 70, 0.55);
    color: #fafafa;
  }

  /* `Button` = primary container. */
  .action.primary {
    border: 1px solid #3b82f6;
    background: #3b82f6;
    color: #ffffff;
  }

  .action.primary:hover:not(:disabled) {
    background: #2563eb;
    border-color: #2563eb;
  }

  .divider {
    width: 100%;
    height: 1px;
    margin: 0;
    border: none;
    background: rgba(63, 63, 70, 0.55);
  }

  /* --- auto-sync switch row ------------------------------------------------------------------ */

  .auto-sync-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .auto-sync-text {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .row-title {
    font-size: 12.5px;
    font-weight: 600;
    color: #fafafa;
  }

  .row-desc {
    font-size: 11px;
    line-height: 1.45;
    color: #a1a1aa;
  }

  /* Material3 `Switch(checked = syncState.autoSyncEnabled)`. */
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
    border: 1px solid #52525b;
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
    border-color: #3b82f6;
    background: #3b82f6;
  }

  .switch input:checked + .switch-track::after {
    background: #ffffff;
    transform: translateX(18px);
  }

  .switch input:focus-visible + .switch-track {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  /* --- advanced fold row --------------------------------------------------------------------- */

  .fold-row {
    display: flex;
    align-items: center;
    gap: 6px;
    align-self: flex-start;
    padding: 4px 0;
    border: none;
    background: transparent;
    color: #a1a1aa;
    font: 500 11px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    transition: color 0.15s;
  }

  .fold-row:hover {
    color: #fafafa;
  }

  .advanced-card {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px;
    border: 1px solid #27272a;
    border-radius: 8px;
    background: rgba(39, 39, 42, 0.6);
  }

  .advanced-head {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .advanced-label {
    flex: 1 1 auto;
    font-size: 10.5px;
    font-weight: 700;
    color: #a1a1aa;
  }

  .icon-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    padding: 0;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .icon-button:hover {
    background: rgba(63, 63, 70, 0.7);
    color: #fafafa;
  }

  /* `FontFamily.Monospace` + `colorScheme.primary`. */
  .advanced-key {
    font: 700 13px/1.35 var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
    color: #60a5fa;
    word-break: break-all;
  }

  .advanced-actions {
    display: flex;
    justify-content: flex-end;
  }

  .text-button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 6px;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: #60a5fa;
    font: 600 11px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .text-button:hover {
    background: rgba(59, 130, 246, 0.14);
    color: #93c5fd;
  }

  .last-synced {
    margin: 0;
    font-size: 10.5px;
    line-height: 1.4;
    color: #a1a1aa;
  }

  /* --- banners ------------------------------------------------------------------------------- */

  .banner {
    padding: 10px;
    border: 1px solid transparent;
    border-radius: 8px;
    font-size: 11.5px;
    line-height: 1.5;
    word-break: break-word;
  }

  /* `errorContainer.copy(alpha = 0.5f)` / `onErrorContainer`. */
  .banner.error {
    border-color: rgba(248, 113, 113, 0.4);
    background: rgba(127, 29, 29, 0.5);
    color: #fecaca;
  }

  /* `primaryContainer.copy(alpha = 0.5f)` / `onPrimaryContainer`. */
  .banner.info {
    border-color: rgba(59, 130, 246, 0.4);
    background: rgba(30, 64, 175, 0.45);
    color: #bfdbfe;
  }

  /* --- pairing dialog ------------------------------------------------------------------------ */

  .pairing-loading {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100px;
  }

  .spinner {
    width: 28px;
    height: 28px;
    border: 3px solid rgba(96, 165, 250, 0.25);
    border-top-color: #60a5fa;
    border-radius: 50%;
    animation: sync-dialog-spin 0.9s linear infinite;
  }

  .pairing-error {
    width: 100%;
  }

  .code-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    width: 100%;
    padding: 16px;
    border: 1px solid rgba(59, 130, 246, 0.4);
    border-radius: 12px;
    background: rgba(59, 130, 246, 0.14);
  }

  .code {
    font: 800 30px/1.1 var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
    letter-spacing: 4px;
    color: #60a5fa;
    font-variant-numeric: tabular-nums;
  }

  .code-timer {
    font-size: 10.5px;
    font-weight: 600;
    color: #a1a1aa;
    font-variant-numeric: tabular-nums;
  }

  .code-timer.expired {
    color: #f87171;
  }

  /* --- connect dialog ------------------------------------------------------------------------ */

  .outlined-field {
    width: 100%;
    padding: 10px 12px;
    border: 1px solid #3f3f46;
    border-radius: 6px;
    background: #09090b;
    color: #fafafa;
    font: 500 13px/1.3 var(--font-sans, system-ui, sans-serif);
  }

  .outlined-field::placeholder {
    color: #71717a;
  }

  .outlined-field:focus {
    outline: none;
    border-color: #60a5fa;
    box-shadow: 0 0 0 2px rgba(96, 165, 250, 0.25);
  }

  /* `headlineSmall` + `Monospace` + `FontWeight.Bold` + centered. */
  .code-field {
    text-align: center;
    font: 700 20px/1.25 var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
    letter-spacing: 3px;
    font-variant-numeric: tabular-nums;
  }

  .mode-row {
    display: flex;
    justify-content: flex-end;
  }

  /* --- dialog buttons (`AlertDialog` confirm / dismiss) -------------------------------------- */

  .dialog-actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    padding: 12px 20px 16px;
  }

  .action :global(.spin) {
    animation: sync-dialog-spin 0.9s linear infinite;
  }

  @keyframes sync-dialog-spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
