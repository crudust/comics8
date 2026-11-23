import { describe, expect, it } from "vitest";
import {
  AUTO_MAX_FALLBACKS,
  HOST_PREFERENCE_TTL_MS,
  ImageFallbackController,
  MANUAL_MAX_FALLBACKS,
} from "../src/viewer/image-fallback";

describe("ImageFallbackController", () => {
  it("caps automatic fallbacks at AUTO_MAX_FALLBACKS (3)", () => {
    const ctrl = new ImageFallbackController();
    const orig = "https://host-a.test/1.jpg";
    const fallbacks = [
      "https://host-b.test/1.jpg",
      "https://host-c.test/1.jpg",
      "https://host-d.test/1.jpg",
      "https://host-e.test/1.jpg",
    ];

    const c1 = ctrl.nextUrl(0, orig, orig, fallbacks, "auto");
    const c2 = ctrl.nextUrl(0, orig, c1!, fallbacks, "auto");
    const c3 = ctrl.nextUrl(0, orig, c2!, fallbacks, "auto");
    const c4 = ctrl.nextUrl(0, orig, c3!, fallbacks, "auto");

    expect([c1, c2, c3]).toEqual([
      "https://host-b.test/1.jpg",
      "https://host-c.test/1.jpg",
      "https://host-d.test/1.jpg",
    ]);
    expect(AUTO_MAX_FALLBACKS).toBe(3);
    expect(c4).toBeNull();
  });

  it("advances past auto limit on manual retry, caps pool at 12, and wraps around", () => {
    const ctrl = new ImageFallbackController();
    const orig = "https://host-0.test/1.jpg";
    const fallbacks = Array.from({ length: 15 }, (_, i) => `https://host-${i + 1}.test/1.jpg`);

    // Exhaust auto attempts (host-1, host-2, host-3)
    let current = orig;
    for (let i = 0; i < AUTO_MAX_FALLBACKS; i += 1) {
      current = ctrl.nextUrl(0, orig, current, fallbacks, "auto")!;
    }
    expect(current).toBe("https://host-3.test/1.jpg");

    // Manual retries continue through host-4..host-11 (since pool = [orig, ...fallbacks].slice(0, 12) = host-0..host-11)
    const manualPicked: string[] = [];
    for (let i = 0; i < 8; i += 1) {
      current = ctrl.nextUrl(0, orig, current, fallbacks, "manual")!;
      manualPicked.push(current);
    }
    expect(manualPicked).toEqual([
      "https://host-4.test/1.jpg",
      "https://host-5.test/1.jpg",
      "https://host-6.test/1.jpg",
      "https://host-7.test/1.jpg",
      "https://host-8.test/1.jpg",
      "https://host-9.test/1.jpg",
      "https://host-10.test/1.jpg",
      "https://host-11.test/1.jpg",
    ]);
    expect(MANUAL_MAX_FALLBACKS).toBe(12);

    // Next manual retry wraps back to the beginning of the capped pool (skipping current host-11)
    const wrapped = ctrl.nextUrl(0, orig, current, fallbacks, "manual");
    expect(wrapped).toBe(orig);
  });

  it("promotes sticky host after 2 recoveries, preserves across resetEpisode, and self-heals on failure", () => {
    let now = 1_000;
    const ctrl = new ImageFallbackController(() => now);
    const orig1 = "https://host-a.test/1.jpg";
    const orig2 = "https://host-a.test/2.jpg";
    const orig3 = "https://host-a.test/3.jpg";
    const fbFor = (u: string) => [
      u.replace("host-a.test", "host-b.test"),
      u.replace("host-a.test", "host-c.test"),
    ];

    // 1st recovery on host-c does not promote yet
    ctrl.recordSuccess(orig1, "https://host-c.test/1.jpg");
    expect(ctrl.hasPreferredHostFor(orig2)).toBe(false);
    expect(ctrl.preferredCandidate(orig2, fbFor(orig2))).toBeNull();

    // 2nd recovery on host-c promotes host-c
    ctrl.recordSuccess(orig2, "https://host-c.test/2.jpg");
    expect(ctrl.hasPreferredHostFor(orig3)).toBe(true);
    expect(ctrl.preferredCandidate(orig3, fbFor(orig3))).toBe("https://host-c.test/3.jpg");

    // resetEpisode clears per-page attempts but retains sticky host
    ctrl.resetEpisode();
    expect(ctrl.hasPreferredHostFor(orig3)).toBe(true);

    // If sticky host-c fails, nextUrl self-heals (drops preference) and falls back to origin/host-b
    const nextAfterStickyFail = ctrl.nextUrl(
      2,
      orig3,
      "https://host-c.test/3.jpg",
      fbFor(orig3),
      "auto",
    );
    expect(ctrl.hasPreferredHostFor(orig3)).toBe(false);
    expect(nextAfterStickyFail).toBe(orig3);

    // Re-promote and verify TTL expiration
    ctrl.recordSuccess(orig1, "https://host-c.test/1.jpg");
    ctrl.recordSuccess(orig2, "https://host-c.test/2.jpg");
    expect(ctrl.hasPreferredHostFor(orig3)).toBe(true);
    now += HOST_PREFERENCE_TTL_MS + 1;
    expect(ctrl.hasPreferredHostFor(orig3)).toBe(false);
  });
});
