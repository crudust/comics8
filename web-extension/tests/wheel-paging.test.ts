import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createWheelPagingController } from "../src/ui/wheel-paging";

function wheel(
  deltaX: number,
  deltaY = 0,
  deltaMode = 0,
): { event: WheelEvent; prevented: () => boolean } {
  let defaultPrevented = false;
  const event = {
    deltaX,
    deltaY,
    deltaMode,
    preventDefault() {
      defaultPrevented = true;
    },
  } as unknown as WheelEvent;
  return { event, prevented: () => defaultPrevented };
}

describe("createWheelPagingController", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-13T12:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("triggers strictly once for an entire trackpad swipe including wobbling inertia and tail rebound", () => {
    const controller = createWheelPagingController({ allowVerticalWheel: true });
    let advances = 0;
    let retreats = 0;
    const input = {
      isR2L: false,
      onAdvance: () => {
        advances += 1;
      },
      onRetreat: () => {
        retreats += 1;
      },
    };

    // Real macOS trackpad flick: ramp up -> peak -> wobbling 600ms inertia -> small rebound -> rest
    const stream: [number, number][] = [
      [6, 0],
      [18, 1], // crosses 15px -> turns 1 page
      [45, 2],
      [70, -1],
      [58, 0],
      [42, 3],
      [30, 0],
      [36, 0], // upward wobble mid-swipe
      [24, 0],
      [18, -2],
      [14, 0],
      [10, 0],
      [15, 0], // upward wobble after cooldown (>350ms)
      [8, 0],
      [6, 0],
      [5, 0],
      [4, 0],
      [-5, 0], // opposite-sign rebound while still decaying
      [2, 0], // below idle threshold (3px) -> releases cleanly
      [-2, 0], // tiny noise after release -> ignored
    ];

    for (const [dx, dy] of stream) {
      vi.advanceTimersByTime(32);
      const { event } = wheel(dx, dy);
      controller.handleWheel(event, input);
    }

    expect(advances).toBe(1);
    expect(retreats).toBe(0);

    // After stream pauses (150ms), a deliberate new swipe triggers the next page
    vi.advanceTimersByTime(200);
    controller.handleWheel(wheel(30, 0).event, input);
    expect(advances).toBe(2);
    expect(retreats).toBe(0);
    controller.destroy();
  });

  it("mirrors horizontal direction in RTL mode", () => {
    const controller = createWheelPagingController({ allowVerticalWheel: true });
    let advances = 0;
    let retreats = 0;

    controller.handleWheel(wheel(-30, 0).event, {
      isR2L: true,
      onAdvance: () => {
        advances += 1;
      },
      onRetreat: () => {
        retreats += 1;
      },
    });

    vi.advanceTimersByTime(400);
    controller.handleWheel(wheel(30, 0).event, {
      isR2L: true,
      onAdvance: () => {
        advances += 1;
      },
      onRetreat: () => {
        retreats += 1;
      },
    });

    expect(advances).toBe(1);
    expect(retreats).toBe(1);
    controller.destroy();
  });

  it("preserves vertical scrolling in SeriesPane mode (allowVerticalWheel: false) while latching horizontal swipes", () => {
    const controller = createWheelPagingController({ allowVerticalWheel: false });
    let advances = 0;
    let retreats = 0;
    const input = {
      onAdvance: () => {
        advances += 1;
      },
      onRetreat: () => {
        retreats += 1;
      },
    };

    // Vertical scroll stream must never turn pages or preventDefault
    for (const dy of [20, 40, 25, 10]) {
      const w = wheel(1, dy);
      controller.handleWheel(w.event, input);
      expect(w.prevented()).toBe(false);
      vi.advanceTimersByTime(16);
    }
    expect(advances).toBe(0);
    expect(retreats).toBe(0);

    // Wait for stream idle, then horizontal swipe -> triggers once and suppresses tail even with vertical noise
    vi.advanceTimersByTime(200);
    const first = wheel(40, 0);
    controller.handleWheel(first.event, input);
    expect(first.prevented()).toBe(true);
    expect(advances).toBe(1);

    vi.advanceTimersByTime(16);
    const noisyTail = wheel(10, 12);
    controller.handleWheel(noisyTail.event, input);
    expect(noisyTail.prevented()).toBe(true);
    expect(advances).toBe(1);

    controller.destroy();
  });

  it("supports hardware line-mode mouse wheels after cooldown", () => {
    const controller = createWheelPagingController({ allowVerticalWheel: true });
    let advances = 0;
    const input = {
      onAdvance: () => {
        advances += 1;
      },
      onRetreat: () => {},
    };

    controller.handleWheel(wheel(0, 1, 1).event, input);
    expect(advances).toBe(1);

    // Within cooldown -> suppressed
    vi.advanceTimersByTime(100);
    controller.handleWheel(wheel(0, 1, 1).event, input);
    expect(advances).toBe(1);

    // Past cooldown -> hardware line notch advances again
    vi.advanceTimersByTime(260);
    controller.handleWheel(wheel(0, 1, 1).event, input);
    expect(advances).toBe(2);
    controller.destroy();
  });

  it("prevents default on sub-idle horizontal wheel events so the browser never triggers history-back swipe", () => {
    for (const allowVerticalWheel of [true, false]) {
      const controller = createWheelPagingController({ allowVerticalWheel });
      const input = {
        onAdvance: () => {},
        onRetreat: () => {},
      };

      // Small ramp-up event (< 3px idleThreshold) at swipe start
      const startSmall = wheel(-2, 0);
      controller.handleWheel(startSmall.event, input);
      expect(startSmall.prevented()).toBe(true);

      // Strong swipe that triggers once
      vi.advanceTimersByTime(16);
      const peak = wheel(-40, 0);
      controller.handleWheel(peak.event, input);
      expect(peak.prevented()).toBe(true);

      // Sub-idle tail event (< 3px idleThreshold) while still horizontally swiping
      vi.advanceTimersByTime(16);
      const tailSmall = wheel(-1, 0);
      controller.handleWheel(tailSmall.event, input);
      expect(tailSmall.prevented()).toBe(true);

      controller.destroy();
    }
  });
});
