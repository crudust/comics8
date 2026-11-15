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

  it("invokes onLoad with natural dimensions when a preloaded image decodes", () => {
    const reported: Array<{ url: string; width: number; height: number }> = [];
    const OriginalImage = globalThis.Image;
    class FakeImage {
      decoding = "async";
      naturalWidth = 1600;
      naturalHeight = 1000;
      complete = true;
      onload: (() => void) | null = null;
      private _src = "";
      set src(value: string) {
        this._src = value;
      }
      get src(): string {
        return this._src;
      }
      addEventListener(): void {}
    }
    globalThis.Image = FakeImage as unknown as typeof Image;
    try {
      preloadImages(["https://example.com/wide.jpg"], (url, width, height) => {
        reported.push({ url, width, height });
      });
      expect(reported).toEqual([{ url: "https://example.com/wide.jpg", width: 1600, height: 1000 }]);
    } finally {
      globalThis.Image = OriginalImage;
    }
  });
});
