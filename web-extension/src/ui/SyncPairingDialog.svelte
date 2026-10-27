<script lang="ts">
  /**
   * Pairing-code dialog, the 1:1 port of `DesktopSyncDialogPairingCode` in
   * `desktop/src/main/kotlin/ui/components/SyncDialog.kt` (lines 301-440), which
   * `SyncBackupSettingsPane.kt` opens from the `actionOpenPairingDialog` button.
   *
   * Kotlin contract, mirrored literally below:
   * - `AlertDialog`: title row `Icons.Default.Devices` + `strings.titlePairNewDevice`,
   *   body = `hintPairingCodeGuide`, then either the loading spinner (`isLoading`), the error banner
   *   (`errorMessage`, error-container @ 0.5), or the code card: the code formatted `123 456`
   *   (`headlineLarge`, monospace, ExtraBold, primary, 4sp tracking), the
   *   `labelPairingTimeLeft(min, sec)` countdown (`errorPairingCodeExpired` once it hits zero), and
   *   the `actionCopy` text button.
   * - confirm = `actionIssueNewCode` (`Refresh`, disabled while loading), dismiss =
   *   `actionClose`.
   * - `LaunchedEffect(Unit) { loadCode() }`: the code is requested as soon as the dialog opens.
   *
   * Port mapping notes:
   * - `viewModel.requestPairingCode { result -> … }` has no callback form here; the store returns a
   *   promise, so {@link loadCode} awaits it and stores the same two facts (`code`,
   *   `expiresInSeconds`).
   * - The countdown is driven by an absolute expiry timestamp instead of a decrementing counter, so a
   *   throttled background tab still reports the true remaining time (identical visible behaviour:
   *   one second less per second, then the expired wording).
   */

  import { onMount } from 'svelte';
  import { Copy, LoaderCircle, MonitorSmartphone, RefreshCw } from '@lucide/svelte';

  import { describeError } from '../messaging';
  import { desktopState } from '../state/desktop-state.svelte';
  import type { DesktopStore } from '../state/desktop-state.svelte';
  import { formatCountdown } from '../views/format';

  interface Props {
    /** Store to drive; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
    /** `onDismiss` of the Kotlin dialog (`actionClose`). */
    onDismiss?: () => void;
  }

  let { store = desktopState, onDismiss }: Props = $props();

  // `KoStrings` values, inlined so the labels cannot drift from the Kotlin dialog.
  const TITLE = '새 기기 연결 (페어링)';
  const HINT =
    '새로 연결할 기기(스마트폰, 태블릿, PC 등)의 [데이터 가져오기] 화면에서 아래 6자리 코드를 입력하세요.';
  const ERROR_EXPIRED = '⚠️ 코드가 만료되었습니다.';
  const ACTION_COPY = '복사';
  const ACTION_NEW_CODE = '새 코드 발급';
  const ACTION_CLOSE = '닫기';

  let loading = $state(true);
  let code = $state('');
  let errorMessage = $state<string | null>(null);
  let expiresAt = $state(0);
  let secondsLeft = $state(0);
  let copied = $state(false);

  /** `{minutes}:{seconds}` split for `labelPairingTimeLeft(minutes, seconds)`. */
  const timerText: string = $derived.by(() => {
    if (secondsLeft <= 0 && code !== '') return ERROR_EXPIRED;
    const minutes = Math.floor(secondsLeft / 60);
    const seconds = secondsLeft % 60;
    return `${minutes}분 ${String(seconds).padStart(2, '0')}초 남음`;
  });

  /** `"${code.substring(0, 3)} ${code.substring(3)}"` when the code is 6 digits. */
  const formattedCode: string = $derived(
    code.length === 6 ? `${code.slice(0, 3)} ${code.slice(3)}` : code,
  );

  let dialogEl = $state<HTMLDivElement | undefined>();

  onMount(() => {
    dialogEl?.focus();
    void loadCode();
  });

  // The countdown reads the absolute expiry, so a late tick still reports the true remainder.
  $effect(() => {
    const deadline = expiresAt;
    if (!deadline) return;
    const tick = (): void => {
      secondsLeft = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
    };
    tick();
    const timer = setInterval(tick, 250);
    return () => clearInterval(timer);
  });

  /** `fun loadCode()` — `POST /pair/request`, re-issued by `actionIssueNewCode`. */
  async function loadCode(): Promise<void> {
    loading = true;
    errorMessage = null;
    copied = false;
    try {
      const result = await store.requestPairingCode();
      code = result.code;
      expiresAt = Date.now() + result.expiresInSeconds * 1000;
      secondsLeft = result.expiresInSeconds;
    } catch (error) {
      code = '';
      expiresAt = 0;
      secondsLeft = 0;
      errorMessage = describeError(error) || '페어링 코드 발급 실패';
    } finally {
      loading = false;
    }
  }

  async function copyCode(): Promise<void> {
    try {
      await navigator.clipboard.writeText(code);
      copied = true;
      setTimeout(() => {
        copied = false;
      }, 1600);
    } catch {
      // A blocked clipboard is not an error worth a second banner in the Kotlin dialog either.
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
  aria-labelledby="pairing-dialog-title"
  tabindex="-1"
  data-testid="sync-pairing-dialog"
>
  <header class="dialog-head">
    <span class="dialog-glyph" aria-hidden="true"><MonitorSmartphone size={22} /></span>
    <h2 class="dialog-title" id="pairing-dialog-title">{TITLE}</h2>
  </header>

  <div class="dialog-body">
    <p class="desc">{HINT}</p>

    {#if loading}
      <div class="loading-box" role="status">
        <LoaderCircle size={28} class="spin" aria-hidden="true" />
      </div>
    {:else if errorMessage}
      <div class="banner error" role="alert">
        <span>{errorMessage}</span>
      </div>
    {:else}
      <div class="code-card">
        <span class="code" aria-label={formattedCode}>{formattedCode}</span>
        <span class="timer" class:expired={secondsLeft <= 0}>{timerText}</span>
      </div>

      <button type="button" class="text-button copy" onclick={copyCode}>
        {#if copied}
          <Copy size={16} aria-hidden="true" />
          <span>복사됨</span>
        {:else}
          <Copy size={16} aria-hidden="true" />
          <span>{ACTION_COPY}</span>
        {/if}
      </button>
    {/if}
  </div>

  <footer class="dialog-actions">
    <button type="button" class="outlined-button close" onclick={dismiss}>{ACTION_CLOSE}</button>
    <button type="button" class="filled-button" disabled={loading} onclick={loadCode}>
      <RefreshCw size={16} aria-hidden="true" />
      <span>{ACTION_NEW_CODE}</span>
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
    width: min(400px, calc(100% - 32px));
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
    align-items: center;
    gap: 14px;
    min-height: 0;
    overflow-y: auto;
  }

  .desc {
    margin: 0;
    align-self: stretch;
    color: #a1a1aa;
    font-size: 12px;
    line-height: 1.45;
  }

  .loading-box {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100px;
    color: #fafafa;
  }

  .banner.error {
    align-self: stretch;
    padding: 12px;
    border-radius: 8px;
    background: rgba(127, 29, 29, 0.5);
    color: #fecaca;
    font-size: 12px;
    line-height: 1.4;
  }

  /* `Surface(12.dp, primaryContainer @ 0.4, primary @ 0.4 border)` around the code. */
  .code-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    width: 100%;
    padding: 16px;
    border: 1px solid rgba(250, 250, 250, 0.4);
    border-radius: 12px;
    background: rgba(250, 250, 250, 0.06);
  }

  .code {
    color: #fafafa;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 34px;
    font-weight: 800;
    letter-spacing: 4px;
    line-height: 1.1;
  }

  .timer {
    color: #a1a1aa;
    font-size: 11px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  .timer.expired {
    color: #f87171;
  }

  .text-button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 6px 10px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #d4d4d8;
    font: 600 11px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .text-button:hover {
    background: rgba(63, 63, 70, 0.6);
    color: #fafafa;
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
    animation: pairing-spin 0.9s linear infinite;
  }

  @keyframes pairing-spin {
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
