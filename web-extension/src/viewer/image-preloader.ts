/**
 * Preload & Pre-decode Engine for Comics8 Reader.
 *
 * Parity with `desktop/src/main/kotlin/ui/util/DesktopImageLoader.kt` (`DesktopImageCache.preload`).
 * In browser environments, pre-fetches upcoming and previous images in memory and triggers
 * native GPU bitmap decoding (`Image.prototype.decode()`) off-thread so that page flips render
 * instantaneously with 0-frame delay and no black-background flashing.
 */

const PRELOAD_CACHE_LIMIT = 20;
const preloadedImages = new Map<string, HTMLImageElement>();

/**
 * Preloads and pre-decodes the given image URLs in the background.
 */
export function preloadImages(urls: (string | undefined | null)[]): void {
  if (typeof Image === 'undefined') return;

  for (const url of urls) {
    if (!url || preloadedImages.has(url)) continue;

    try {
      const img = new Image();
      img.decoding = 'async';
      img.src = url;

      if (typeof img.decode === 'function') {
        img.decode().catch(() => {
          // Decoding error on background preload is ignored; normal render will retry/handle.
        });
      }

      preloadedImages.set(url, img);

      // Keep cache bounded
      if (preloadedImages.size > PRELOAD_CACHE_LIMIT) {
        const oldest = preloadedImages.keys().next().value;
        if (oldest) preloadedImages.delete(oldest);
      }
    } catch {
      // Ignore background preload exceptions
    }
  }
}

/** Clears the in-memory preloaded image references. */
export function clearPreloadCache(): void {
  preloadedImages.clear();
}

/** Returns the number of preloaded image objects in memory (for testing). */
export function getPreloadedCount(): number {
  return preloadedImages.size;
}
