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
 * When `onLoad` is supplied, reports decoded natural dimensions (`naturalWidth`, `naturalHeight`)
 * so callers can record aspect ratios before the page enters the viewport.
 */
export function preloadImages(
  urls: (string | undefined | null)[],
  onLoad?: (url: string, width: number, height: number) => void,
): void {
  if (typeof Image === 'undefined') return;

  for (const url of urls) {
    if (!url) continue;

    const existing = preloadedImages.get(url);
    if (existing) {
      if (onLoad) {
        if (existing.naturalWidth > 0 && existing.naturalHeight > 0) {
          onLoad(url, existing.naturalWidth, existing.naturalHeight);
        } else {
          existing.addEventListener(
            'load',
            () => {
              if (existing.naturalWidth > 0 && existing.naturalHeight > 0) {
                onLoad(url, existing.naturalWidth, existing.naturalHeight);
              }
            },
            { once: true },
          );
        }
      }
      continue;
    }

    try {
      const img = new Image();
      img.decoding = 'async';
      const notifyLoaded = () => {
        if (onLoad && img.naturalWidth > 0 && img.naturalHeight > 0) {
          onLoad(url, img.naturalWidth, img.naturalHeight);
        }
      };
      img.onload = notifyLoaded;
      img.src = url;
      if (img.complete) notifyLoaded();

      if (typeof img.decode === 'function') {
        img.decode().then(notifyLoaded).catch(() => {
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
