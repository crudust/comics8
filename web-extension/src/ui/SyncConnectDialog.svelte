<script lang="ts">
  /**
   * Connect-device dialog, the 1:1 port of `DesktopSyncDialogConnectDevice` in
   * `desktop/src/main/kotlin/ui/components/SyncDialog.kt` (lines 442-617), which
   * `SyncBackupSettingsPane.kt` opens from the `actionOpenConnectDialog` button.
   *
   * Kotlin contract, mirrored literally below:
   * - `AlertDialog`: title row `Icons.Default.CloudSync` + `titleImportExistingData`
   *   (or `titleConnectWithMasterKey` in key mode), body = the mode's hint, its field, the
   *   right-aligned text-button mode switch, and one status banner (error container when `isError`,
   *   primary container otherwise).
   * - Pairing-code mode: `hintInput6DigitCode`, a centered monospace `headlineSmall` field that keeps
   *   digits only and caps at 6 (`placeholder6DigitCode`), and `actionSwitchToMasterKeyMode`.
   * - Master-key mode: `hintMasterKeyGuide`, a single-line field that trims + upper-cases
   *   (`placeholderMasterKey`), and `actionSwitchToPairingCodeMode`.
   * - confirm = `actionImportData` (`statusConnecting` + spinner while in flight), disabled until the
   *   6-digit code is complete or the key is non-blank. It calls `confirmPairingCode` in code mode
   *   and `restoreWithMasterKey` in key mode, closing only on success.
   * - dismiss = `actionCancel`.
   *
   * Port mapping notes:
   * - `restoreWithMasterKey` is installed into the store by
   *   `DesktopStore.updateSyncKey` (the web twin: the browser stores expose no bulk writer, so the
   *   key is installed and the next sync pulls the rows back through `mergeRemoteHistory`).
   * - The Kotlin callback pair (`{ result -> … }`) becomes one awaited promise, so success and
   *   failure land in the same `try`/`catch` the desktop's `isError` / `statusMessage` pair encodes.
   */

  import { CloudSync, LoaderCircle } from 'lucide-svelte';

  import { describeError } from '../messaging';
  import { desktopState } from '../state/desktop-state.svelte';
  import type { DesktopStore } from '../state/desktop-state.svelte';

  interface Props {
    /** Store to drive; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
    /** `onDismiss` of the Kotlin dialog (`actionCancel`). */
    onDismiss?: () => void;
  }

  let { store = desktopState, onDismiss }: Props = $props();

  // `KoStrings` values, inlined so the labels cannot drift from the Kotlin dialog.
  const TITLE_CODE = '기존 데이터 가져오기';
  const TITLE_KEY = '마스터 복구 키로 연결';
  const HINT_CODE = '기존에 사용하던 기기의 [새 기기 연결하기]에 표시된 6자리 번호를 입력하세요.';
  const HINT_KEY =
    '이전에 저장해 둔 마스터 복구 키(C8-XXXX-XXXX-XXXX)를 입력하여 데이터를 직접 복원합니다.';
  const PLACEHOLDER_CODE = '예: 849201';
  const PLACEHOLDER_KEY = '예: C8-7X9K-2M4Q-99PL';
  const ACTION_SWITCH_TO_KEY = '기존 기기가 없나요? 마스터 복구 키로 복원';
  const ACTION_SWITCH_TO_CODE = '6자리 페어링 코드로 입력하기';
  const STATUS_CONNECTING = '연결 중...';
  const ACTION_IMPORT_DATA = '데이터 가져오기';
  const ACTION_CANCEL = '취소';
  const MSG_DATA_IMPORT_FAILED = '데이터 가져오기 실패';
  const MSG_INVALID_MASTER_KEY = '올바른 마스터 복구 키 형식이 아닙니다.';

  const CODE_LENGTH = 6;

  let codeInput = $state('');
  let keyInput = $state('');
  let useRecoveryKeyMode = $state(false);
  let connecting = $state(false);
  let statusMessage = $state<string | null>(null);
  let isError = $state(false);
  let dialogEl = $state<HTMLDivElement | undefined>();

  const title = $derived(useRecoveryKeyMode ? TITLE_KEY : TITLE_CODE);
  const canConfirm = $derived(
    !connecting && (useRecoveryKeyMode ? keyInput.trim() !== '' : codeInput.length === CODE_LENGTH),
  );

  $effect(() => {
    dialogEl?.focus();
  });

  /** `onValueChange = { it.filter { ch -> ch.isDigit() }.take(6) }`. */
  function updateCode(value: string): void {
    codeInput = value.replace(/\D/g, '').slice(0, CODE_LENGTH);
  }

  /** `onValueChange = { recoveryKeyInput = it.trim().uppercase() }`. */
  function updateKey(value: string): void {
    keyInput = value.trim().toUpperCase();
  }

  function switchMode(toKeyMode: boolean): void {
    useRecoveryKeyMode = toKeyMode;
    statusMessage = null;
    isError = false;
  }

  /** The Kotlin `confirmButton` body: one path per mode, closing only on success. */
  async function confirm(): Promise<void> {
    if (!canConfirm) return;
    connecting = true;
    statusMessage = null;
    isError = false;
    try {
      if (!useRecoveryKeyMode) {
        await store.confirmPairingCode(codeInput);
      } else {
        const key = keyInput.trim();
        if (key === '') {
          isError = true;
          statusMessage = MSG_INVALID_MASTER_KEY;
          return;
        }
        await store.updateSyncKey(key);
      }
      dismiss();
    } catch (error) {
      isError = true;
      statusMessage = describeError(error) || MSG_DATA_IMPORT_FAILED;
    } finally {
      connecting = false;
    }
  }

  function dismiss(): void {
    onDismiss?.();
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    event.preventDefault();
    event.stopPropagation();
    dismiss();
  }

  function onBackdropClick(event: MouseEvent): void {
    if (event.target !== event.currentTarget) return;
    dismiss();
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div class="dialog-backdrop" role="presentation" onclick={onBackdropClick}></div>

<div
  bind:this={dialogEl}
  class="dialog"
  role="dialog"
  aria-modal="true"
  aria-labelledby="connect-dialog-title"
  tabindex="-1"
  data-testid="sync-connect-dialog"
>
  <header class="dialog-head">
    <span class="dialog-glyph" aria-hidden="true"><CloudSync size={22} /></span>
    <h2 class="dialog-title" id="connect-dialog-title">{title}</h2>
  </header>

  <div class="dialog-body">
    {#if !useRecoveryKeyMode}
      <p class="desc">{HINT_CODE}</p>

      <input
        class="field code"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        maxlength={CODE_LENGTH}
        placeholder={PLACEHOLDER_CODE}
        aria-label="6자리 페어링 코드"
        value={codeInput}
        oninput={(event) => updateCode(event.currentTarget.value)}
      />

      <div class="switch-row">
        <button type="button" class="text-button" onclick={() => switchMode(true)}>
          {ACTION_SWITCH_TO_KEY}
        </button>
      </div>
    {:else}
      <p class="desc">{HINT_KEY}</p>

      <input
        class="field key"
        type="text"
        autocomplete="off"
        spellcheck="false"
        placeholder={PLACEHOLDER_KEY}
        aria-label="마스터 복구 키"
        value={keyInput}
        oninput={(event) => updateKey(event.currentTarget.value)}
      />

      <div class="switch-row">
        <button type="button" class="text-button" onclick={() => switchMode(false)}>
          {ACTION_SWITCH_TO_CODE}
        </button>
      </div>
    {/if}

    {#if statusMessage}
      <div class="banner" class:error={isError} role={isError ? 'alert' : 'status'}>
        {statusMessage}
      </div>
    {/if}
  </div>

  <footer class="dialog-actions">
    <button type="button" class="outlined-button cancel" onclick={dismiss}>{ACTION_CANCEL}</button>
    <button type="button" class="filled-button" disabled={!canConfirm} onclick={confirm}>
      {#if connecting}
        <LoaderCircle size={16} class="spin" aria-hidden="true" />
        <span>{STATUS_CONNECTING}</span>
      {:else}
        <span>{ACTION_IMPORT_DATA}</span>
      {/if}
    </button>
  </footer>
</div>

<style>
  .dialog-backdrop {
    position: fixed;
    inset: 0;
    z-index: 70;
    background: rgba(0, 0, 0, 0.66);
  }

  .dialog {
    position: fixed;
    top: 50%;
    left: 50%;
    z-index: 71;
    display: flex;
    flex-direction: column;
    width: min(420px, calc(100% - 32px));
    max-height: calc(100% - 48px);
    overflow: hidden;
    padding: 22px 24px 12px;
    border: 1px solid rgba(63, 63, 70, 0.6);
    border-radius: 28px;
    background: #18181b;
    color: #f4f4f5;
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.6);
    transform: translate(-50%, -50%);
  }

  .dialog:focus {
    outline: none;
  }

  .dialog-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-bottom: 12px;
  }

  .dialog-glyph {
    display: inline-flex;
    flex: 0 0 auto;
    color: #fafafa;
  }

  .dialog-title {
    margin: 0;
    color: #fafafa;
    font-size: 16px;
    font-weight: 700;
    line-height: 1.3;
  }

  .dialog-body {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 12px;
    min-height: 0;
    overflow-y: auto;
  }

  .desc {
    margin: 0;
    color: #a1a1aa;
    font-size: 12px;
    line-height: 1.45;
  }

  /* `OutlinedTextField(singleLine)`. */
  .field {
    width: 100%;
    padding: 10px 12px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.45);
    color: #f4f4f5;
    box-sizing: border-box;
  }

  .field:focus {
    outline: none;
    border-color: rgba(250, 250, 250, 0.6);
    box-shadow: 0 0 0 3px rgba(63, 63, 70, 0.5);
  }

  /* `headlineSmall + Monospace + Bold + TextAlign.Center`. */
  .field.code {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 20px;
    font-weight: 700;
    letter-spacing: 0.2em;
    text-align: center;
  }

  .field.key {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
    font-weight: 600;
  }

  .field::placeholder {
    color: #52525b;
    font-weight: 400;
    letter-spacing: normal;
  }

  .switch-row {
    display: flex;
    justify-content: flex-end;
  }

  .text-button {
    padding: 4px 8px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #a1a1aa;
    font: 600 11px/1.35 var(--font-sans, system-ui, sans-serif);
    text-align: right;
    cursor: pointer;
  }

  .text-button:hover {
    background: rgba(63, 63, 70, 0.6);
    color: #fafafa;
  }

  .banner {
    padding: 10px;
    border-radius: 8px;
    background: rgba(250, 250, 250, 0.08);
    color: #e4e4e7;
    font-size: 12px;
    line-height: 1.4;
  }

  .banner.error {
    background: rgba(127, 29, 29, 0.5);
    color: #fecaca;
  }

  .dialog-actions {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    padding-top: 12px;
  }

  .outlined-button {
    padding: 8px 14px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 8px;
    background: transparent;
    color: #e4e4e7;
    font: 600 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .outlined-button:hover {
    border-color: rgba(161, 161, 170, 0.8);
    color: #fafafa;
  }

  .filled-button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 14px;
    border: none;
    border-radius: 8px;
    background: #fafafa;
    color: #000000;
    font: 700 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .filled-button:hover:not(:disabled) {
    background: #e4e4e7;
  }

  .filled-button:disabled {
    opacity: 0.5;
    cursor: default;
  }

  :global(.spin) {
    animation: connect-spin 0.9s linear infinite;
  }

  @keyframes connect-spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    :global(.spin) {
      animation: none;
    }
  }
</style>
