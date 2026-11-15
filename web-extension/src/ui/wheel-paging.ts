/**
 * Discrete trackpad / mouse-wheel paging recognizer.
 *
 * Parity with `desktopDiscreteScrollPaging` in `desktop/src/main/kotlin/ui/components/CommonWidgets.kt`
 * and `ReaderDomain.resolveScrollGestureAxis` / `resolveScrollDirection` in `core`, scaled to
 * browser `WheelEvent` pixel deltas:
 * - `SCROLL_IDLE_THRESHOLD` (3 px) is 1/5th of `SCROLL_PAGE_THRESHOLD` (15 px), matching
 *   `ReaderDomain`'s 0.2f / 1.0f ratio so inertia cannot unlatch before decaying below page trigger.
 * - While latched, continuation/inertia events in the active stream are suppressed unconditionally
 *   until the stream goes idle (`SCROLL_STREAM_IDLE_MS`) or decays below `SCROLL_IDLE_THRESHOLD`
 *   after `SCROLL_LATCH_COOLDOWN_MS`.
 */

export const SCROLL_PAGE_THRESHOLD = 15.0;
export const SCROLL_IDLE_THRESHOLD = 3.0;
export const SCROLL_LATCH_COOLDOWN_MS = 350;
export const SCROLL_STREAM_IDLE_MS = 150;

export type ScrollGestureAxis = 'HORIZONTAL' | 'VERTICAL' | 'UNDECIDED';

export function normalizeWheelDelta(delta: number, deltaMode: number): number {
  if (!Number.isFinite(delta)) return 0;
  if (deltaMode === 1) return delta * 32; // DOM_DELTA_LINE
  if (deltaMode === 2) return delta * 800; // DOM_DELTA_PAGE
  return delta;
}

export interface WheelPagingOptions {
  allowVerticalWheel?: boolean;
}

export interface WheelPagingInput {
  isR2L?: boolean;
  enabled?: boolean;
  onAdvance: () => void;
  onRetreat: () => void;
}

export interface WheelPagingController {
  handleWheel(event: WheelEvent, input: WheelPagingInput): void;
  destroy(): void;
}

export function createWheelPagingController(
  options: WheelPagingOptions = {},
): WheelPagingController {
  const allowVerticalWheel = options.allowVerticalWheel ?? true;

  let isLatched = false;
  let lastTriggerTime = 0;
  let lastEventTime = 0;
  let accumX = 0;
  let accumY = 0;
  let streamAxis: ScrollGestureAxis = 'UNDECIDED';
  let idleTimer: ReturnType<typeof setTimeout> | null = null;

  function clearIdleTimer(): void {
    if (idleTimer !== null) {
      clearTimeout(idleTimer);
      idleTimer = null;
    }
  }

  function resetStream(): void {
    isLatched = false;
    accumX = 0;
    accumY = 0;
    streamAxis = 'UNDECIDED';
  }

  function destroy(): void {
    clearIdleTimer();
    resetStream();
  }

  function handleWheel(event: WheelEvent, input: WheelPagingInput): void {
    const enabled = input.enabled ?? true;
    const isR2L = input.isR2L ?? false;

    const deltaX = normalizeWheelDelta(event.deltaX, event.deltaMode);
    const deltaY = normalizeWheelDelta(event.deltaY, event.deltaMode);
    const absX = Math.abs(deltaX);
    const absY = Math.abs(deltaY);
    const maxAbs = Math.max(absX, absY);

    const now = Date.now();
    const elapsed = now - lastTriggerTime;
    const sinceLastEvent = now - lastEventTime;
    lastEventTime = now;

    // If the wheel stream paused long enough and cooldown elapsed, start fresh.
    if (sinceLastEvent >= SCROLL_STREAM_IDLE_MS && elapsed >= SCROLL_LATCH_COOLDOWN_MS) {
      resetStream();
    }

    clearIdleTimer();
    const remainingCooldown = Math.max(0, SCROLL_LATCH_COOLDOWN_MS - elapsed);
    idleTimer = setTimeout(() => {
      idleTimer = null;
      resetStream();
    }, Math.max(SCROLL_STREAM_IDLE_MS, remainingCooldown));

    // Lock dominant axis once (`ReaderDomain.resolveScrollGestureAxis`).
    if (streamAxis === 'UNDECIDED' && maxAbs >= SCROLL_IDLE_THRESHOLD) {
      if (absY - absX > SCROLL_IDLE_THRESHOLD) {
        streamAxis = 'VERTICAL';
      } else if (absX - absY > SCROLL_IDLE_THRESHOLD) {
        streamAxis = 'HORIZONTAL';
      }
    }

    // List/grid containers (`allowVerticalWheel: false`) own vertical scrolling natively.
    if (
      !allowVerticalWheel &&
      (streamAxis === 'VERTICAL' || (streamAxis !== 'HORIZONTAL' && absY >= absX))
    ) {
      return;
    }

    // Consume horizontal and reader-surface events immediately (including sub-idle deltas) so
    // Chrome never escalates a heavy trackpad swipe into history-back/forward overscroll.
    if (absX > 0 || absY > 0) {
      event.preventDefault();
    }

    // Momentum decayed below idle threshold: release latch once cooldown has passed.
    if (absX < SCROLL_IDLE_THRESHOLD && absY < SCROLL_IDLE_THRESHOLD) {
      if (elapsed >= SCROLL_LATCH_COOLDOWN_MS) {
        isLatched = false;
        accumX = 0;
        accumY = 0;
      }
      return;
    }

    // Suppress continuation/inertia events while latched (`CommonWidgets.kt` lines 332-336).
    // Hardware line/page wheels (`deltaMode !== 0`) are never trackpad inertia and may re-trigger
    // once cooldown has elapsed.
    if (isLatched) {
      if (event.deltaMode !== 0 && elapsed >= SCROLL_LATCH_COOLDOWN_MS) {
        isLatched = false;
        accumX = 0;
        accumY = 0;
      } else {
        return;
      }
    }

    if (!enabled || elapsed < SCROLL_LATCH_COOLDOWN_MS) {
      return;
    }

    // Reset accumulator if direction flips within an unlatched stream so opposing jitters don't fight.
    if (accumX !== 0 && deltaX !== 0 && Math.sign(deltaX) !== Math.sign(accumX)) {
      accumX = 0;
    }
    if (accumY !== 0 && deltaY !== 0 && Math.sign(deltaY) !== Math.sign(accumY)) {
      accumY = 0;
    }

    accumX += deltaX;
    accumY += allowVerticalWheel ? deltaY : 0;

    const accAbsX = Math.abs(accumX);
    const accAbsY = Math.abs(accumY);
    if (accAbsX < SCROLL_PAGE_THRESHOLD && accAbsY < SCROLL_PAGE_THRESHOLD) {
      return;
    }

    const direction: 'advance' | 'retreat' =
      accAbsX >= accAbsY
        ? (isR2L ? accumX < 0 : accumX > 0)
          ? 'advance'
          : 'retreat'
        : accumY > 0
          ? 'advance'
          : 'retreat';

    isLatched = true;
    lastTriggerTime = now;
    accumX = 0;
    accumY = 0;

    if (direction === 'advance') {
      input.onAdvance();
    } else {
      input.onRetreat();
    }
  }

  return {
    handleWheel,
    destroy,
  };
}
