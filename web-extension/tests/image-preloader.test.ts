// @vitest-environment happy-dom
import { describe, expect, it, beforeEach } from "vitest";
import {
  preloadImages,
  clearPreloadCache,
  getPreloadedCount,
} from "../src/viewer/image-preloader";

describe("image-preloader", () => {
  beforeEach(() => {
    clearPreloadCache();
  });

  it("preloads and tracks unique image URLs", () => {
    expect(getPreloadedCount()).toBe(0);

    preloadImages(["https://example.com/1.jpg", "https://example.com/2.jpg"]);
    expect(getPreloadedCount()).toBe(2);

    // Duplicates are ignored
    preloadImages(["https://example.com/1.jpg", "https://example.com/3.jpg"]);
    expect(getPreloadedCount()).toBe(3);
  });

  it("ignores undefined, null and blank URLs safely", () => {
    preloadImages([null, undefined, "", "https://example.com/valid.jpg"]);
    expect(getPreloadedCount()).toBe(1);
  });

  it("caps memory cache to bounded limit", () => {
    const urls = Array.from({ length: 30 }, (_, i) => `https://example.com/${i}.jpg`);
    preloadImages(urls);
    expect(getPreloadedCount()).toBeLessThanOrEqual(20);
  });

  it("clears cache cleanly", () => {
    preloadImages(["https://example.com/1.jpg"]);
    expect(getPreloadedCount()).toBe(1);
    clearPreloadCache();
    expect(getPreloadedCount()).toBe(0);
  });
});
