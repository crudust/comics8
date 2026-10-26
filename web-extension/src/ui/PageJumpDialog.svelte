<script lang="ts">
  /**
   * Modal page-number jump, ported from `desktop/src/main/kotlin/ui/components/PageJumpDialog.kt`.
   *
   * Kotlin contract:
   * - the title is `strings.titlePageJumpRange(maxPage)` → `페이지 이동 (1 ~ N)`;
   * - the field is digits only (`input.all { it.isDigit() }`), single line, numeric keyboard;
   * - confirming parses, clamps into `1..maxPage` and falls back to `current` when the field is not
   *   a number, then dismisses itself (`onConfirm(target)` followed by `onDismiss()` in the Kotlin
   *   `Button`), so the caller only has to move the page;
   * - the cancel button (`strings.actionCancel`) and a back/Escape both dismiss.
   *
   * The dialog is presentational: the reader owns the current page and the page-change callback.
   */

  import { untrack } from "svelte";

  interface Props {
    /** 1-based page currently shown; also the field's initial value. */
    current: number;
    /** 1-based last page; clamped to at least 1 (`last.coerceAtLeast(1)`). */
    last: number;
    /** Dismiss without jumping (cancel button, backdrop, Escape). */
    onDismiss?: () => void;
    /** Confirmed target, already clamped into `1..last`. */
    onConfirm?: (page: number) => void;
  }

  let { current, last, onDismiss, onConfirm }: Props = $props();

  const maxPage = $derived(Math.max(1, Math.trunc(last || 1)));
  const title = $derived(`페이지 이동 (1 ~ ${maxPage})`);

  // Seeded from the page the dialog opened on (`remember { mutableStateOf(current.toString()) }`);
  // `untrack` keeps this a one-time snapshot instead of a state-initialiser dependency.
  let text = $state(String(Math.trunc(untrack(() => current) || 1)));

  // Kotlin keeps the field in sync with the page it opened on; re-seed when the caller re-targets.
  $effect(() => {
    text = String(Math.trunc(current || 1));
  });

  let input: HTMLInputElement | undefined = $state();

  $effect(() => {
    input?.focus();
    input?.select();
  });

  function onInput(event: Event): void {
    const value = (event.currentTarget as HTMLInputElement).value;
    // `if (input.isEmpty() || input.all { it.isDigit() })`: reject pasted junk instead of clamping it.
    if (value === "" || /^\d+$/.test(value)) text = value;
  }

  function confirm(): void {
    const parsed = Number.parseInt(text, 10);
    const target = Number.isFinite(parsed)
      ? Math.min(Math.max(parsed, 1), maxPage)
      : Math.trunc(current || 1);
    onConfirm?.(target);
    onDismiss?.();
  }

  /** Escape belongs to the dialog while it is open, not to the reader behind it. */
  function onKeydown(event: KeyboardEvent): void {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onDismiss?.();
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      event.stopPropagation();
      confirm();
    }
  }
</script>

{#if true}
<div class="dialog-backdrop" role="presentation" onclick={() => onDismiss?.()}>
    <div
      class="dialog page-jump-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="page-jump-title"
      tabindex="-1"
      data-testid="page-jump-dialog"
      onclick={(event) => event.stopPropagation()}
      onkeydown={onKeydown}
    >
      <h2 class="dialog-title" id="page-jump-title">{title}</h2>

      <label class="field">
        <span class="field-label">이동할 페이지 번호</span>
        <input
          class="field-input"
          bind:this={input}
          type="text"
          inputmode="numeric"
          autocomplete="off"
          value={text}
          oninput={onInput}
        />
      </label>

      <div class="dialog-actions">
        <button type="button" class="dialog-button cancel" onclick={() => onDismiss?.()}>
          취소
        </button>
        <button type="button" class="dialog-button confirm" onclick={confirm}>이동</button>
      </div>
    </div>
  </div>
{/if}

<style>
  /* Kotlin `AlertDialog` scrim; zinc monochrome, no accent colour. */
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

  .dialog {
    display: flex;
    flex-direction: column;
    gap: 16px;
    width: min(360px, 100%);
    padding: 20px;
    border: 1px solid #27272a;
    border-radius: 16px;
    background: #18181b;
    color: #f4f4f5;
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.6);
  }

  .dialog-title {
    margin: 0;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .field-label {
    font-size: 11.5px;
    font-weight: 600;
    color: #a1a1aa;
  }

  .field-input {
    width: 100%;
    padding: 9px 12px;
    border: 1px solid #3f3f46;
    border-radius: 8px;
    background: #09090b;
    color: #f4f4f5;
    font: 600 14px/1.3 var(--font-sans, system-ui, sans-serif);
    font-variant-numeric: tabular-nums;
  }

  .field-input:focus {
    outline: none;
    border-color: #71717a;
    box-shadow: 0 0 0 2px rgba(113, 113, 122, 0.25);
  }

  .dialog-actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
  }

  .dialog-button {
    padding: 7px 16px;
    border-radius: 8px;
    font: 600 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s;
  }

  .dialog-button.cancel {
    border: 1px solid transparent;
    background: transparent;
    color: #a1a1aa;
  }

  .dialog-button.cancel:hover {
    background: rgba(63, 63, 70, 0.4);
    color: #e4e4e7;
  }

  .dialog-button.confirm {
    border: 1px solid #3f3f46;
    background: #27272a;
    color: #fafafa;
  }

  .dialog-button.confirm:hover {
    background: #3f3f46;
    border-color: #52525b;
  }
</style>
