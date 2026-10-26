/**
 * ZIP / CBZ reading for the extension, using `fflate`.
 *
 * Contract notes (mirroring `core/src/main/kotlin/source/local/ZipImageNames.kt` and
 * `NaturalSort.kt`, which are the canonical owners of entry filtering and page ordering):
 *
 * - junk entries (`__MACOSX`, dotfiles) and non-image extensions are dropped;
 * - pages sort naturally, so `1.jpg < 2.jpg < 10.jpg`;
 * - every page becomes an `ObjectURL` that must be revoked — {@link ZipProvider.close} does it.
 *
 * Extraction is lazy: {@link ZipProvider.open} only reads the central directory so a 500 MB CBZ
 * does not decompress until the reader actually asks for pages.
 */

import { unzip, type Unzipped } from "fflate";

export const IMAGE_EXTENSIONS = ["jpg", "jpeg", "png", "webp", "avif", "gif"] as const;
export const ARCHIVE_EXTENSIONS = ["zip", "cbz"] as const;

const MIME_BY_EXT: Record<string, string> = {
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
  avif: "image/avif",
  gif: "image/gif",
};

/** One extracted page. */
export type ComicPage = {
  /** Entry path inside the archive, e.g. `chapter01/010.jpg`. */
  name: string;
  /** File name without directories, for display. */
  fileName: string;
  /** `URL.createObjectURL(blob)` result. */
  url: string;
  /** The underlying blob, so callers can download/export without re-inflating. */
  blob: Blob;
  bytes: number;
  mimeType: string;
};

export type ZipEntryInfo = {
  name: string;
  fileName: string;
  bytes: number;
  mimeType: string;
};

export type OpenArchiveOptions = {
  /** Overrides the MIME guess (e.g. when the extension lies). */
  mimeType?: (entryName: string) => string | undefined;
  /** Abort early; useful when opening a remote archive. */
  signal?: AbortSignal;
};

export function isImageEntry(name: string): boolean {
  if (isJunkEntry(name) || name.endsWith("/")) return false;
  const ext = name.slice(name.lastIndexOf(".") + 1).toLowerCase();
  return (IMAGE_EXTENSIONS as readonly string[]).includes(ext);
}

export function isArchiveName(name: string): boolean {
  if (isJunkEntry(name) || name.endsWith("/")) return false;
  const ext = archiveExtension(name);
  return ext !== null;
}

/** Returns `"zip" | "cbz"` for archive names, otherwise `null`. */
export function archiveExtension(name: string): (typeof ARCHIVE_EXTENSIONS)[number] | null {
  const lower = name.toLowerCase();
  const dot = lower.lastIndexOf(".");
  if (dot < 0) return null;
  const ext = lower.slice(dot + 1);
  return (ARCHIVE_EXTENSIONS as readonly string[]).includes(ext)
    ? (ext as (typeof ARCHIVE_EXTENSIONS)[number])
    : null;
}

export function isJunkEntry(name: string): boolean {
  const normalized = name.replace(/\\/g, "/");
  if (normalized.startsWith("__MACOSX") || normalized.includes("/__MACOSX/")) return true;
  return isJunkName(baseName(normalized));
}

export function isJunkName(name: string): boolean {
  if (name === "" || name === "." || name === "..") return true;
  if (name === "__MACOSX" || name.startsWith(".")) return true;
  return name.replace(/\\/g, "/").startsWith("__MACOSX");
}

export function baseName(name: string): string {
  const normalized = name.replace(/\\/g, "/").replace(/\/+$/, "");
  const index = normalized.lastIndexOf("/");
  return index < 0 ? normalized : normalized.slice(index + 1);
}

export function mimeTypeFor(name: string): string {
  const ext = name.slice(name.lastIndexOf(".") + 1).toLowerCase();
  return MIME_BY_EXT[ext] ?? "application/octet-stream";
}

/**
 * Natural comparison: digit runs compare by numeric value, and equal values put the shorter run
 * first (`1` < `01` < `001`). Non-digits compare case-insensitively, then by code point, exactly like
 * `NaturalSort` in `core`.
 */
export function naturalCompare(a: string, b: string): number {
  if (a === b) return 0;

  let ia = 0;
  let ib = 0;
  const lenA = a.length;
  const lenB = b.length;

  while (ia < lenA && ib < lenB) {
    const isDigitA = isDigit(a[ia]);
    const startA = ia;
    while (ia < lenA && isDigit(a[ia]) === isDigitA) ia += 1;
    const endA = ia;

    const isDigitB = isDigit(b[ib]);
    const startB = ib;
    while (ib < lenB && isDigit(b[ib]) === isDigitB) ib += 1;
    const endB = ib;

    if (isDigitA && isDigitB) {
      let nzA = startA;
      while (nzA < endA && a[nzA] === "0") nzA += 1;
      let nzB = startB;
      while (nzB < endB && b[nzB] === "0") nzB += 1;

      const numLenA = endA - nzA;
      const numLenB = endB - nzB;
      if (numLenA !== numLenB) return numLenA - numLenB;
      for (let k = 0; k < numLenA; k += 1) {
        const ca = a[nzA + k];
        const cb = b[nzB + k];
        if (ca !== cb) return ca < cb ? -1 : 1;
      }
      const rawLenA = endA - startA;
      const rawLenB = endB - startB;
      if (rawLenA !== rawLenB) return rawLenA - rawLenB;
    } else {
      const tokenLenA = endA - startA;
      const tokenLenB = endB - startB;
      const minLen = Math.min(tokenLenA, tokenLenB);
      for (let k = 0; k < minLen; k += 1) {
        let ca = a[startA + k];
        let cb = b[startB + k];
        if (ca !== cb) {
          ca = ca.toUpperCase();
          cb = cb.toUpperCase();
          if (ca !== cb) {
            ca = ca.toLowerCase();
            cb = cb.toLowerCase();
            if (ca !== cb) return ca < cb ? -1 : 1;
          }
        }
      }
      if (tokenLenA !== tokenLenB) return tokenLenA - tokenLenB;
    }
  }

  const remainingA = countRemainingTokens(a, ia);
  const remainingB = countRemainingTokens(b, ib);
  if (remainingA !== remainingB) return remainingA - remainingB;

  return a < b ? -1 : a > b ? 1 : 0;
}

/** Sorts image entry names into reading order. Non-mutating. */
export function sortPageNames(names: readonly string[]): string[] {
  return [...names].sort(naturalCompare);
}

function isDigit(char: string | undefined): boolean {
  return char !== undefined && char >= "0" && char <= "9";
}

function countRemainingTokens(value: string, from: number): number {
  if (from >= value.length) return 0;
  let count = 0;
  let index = from;
  while (index < value.length) {
    const digit = isDigit(value[index]);
    count += 1;
    while (index < value.length && isDigit(value[index]) === digit) index += 1;
  }
  return count;
}

/** Locally generated object URLs, revoked by the provider. */
function createObjectUrl(blob: Blob): string {
  return URL.createObjectURL(blob);
}

export class ZipProvider {
  private readonly pages: ComicPage[];
  private readonly metadata: { totalEntries: number; skippedEntries: number };
  private closed = false;

  private constructor(pages: ComicPage[], metadata: { totalEntries: number; skippedEntries: number }) {
    this.pages = pages;
    this.metadata = metadata;
  }

  /**
   * Decompresses an archive and materialises every image page as an object URL.
   *
   * `data` may be a `Uint8Array` (local file / background fetch) or an `ArrayBuffer`.
   */
  static async open(
    data: Uint8Array | ArrayBuffer,
    options: OpenArchiveOptions = {},
  ): Promise<ZipProvider> {
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    if (bytes.byteLength === 0) throw new Error("ZIP archive is empty");

    const entries = await unzipAsync(bytes);
    throwIfAborted(options.signal);

    const names = Object.keys(entries).filter(isImageEntry);
    if (names.length === 0) {
      const skipped = Object.keys(entries).filter((name) => !isJunkEntry(name) && !name.endsWith("/"));
      throw new Error(
        `ZIP archive contains no comic images (${skipped.length} non-image entries found)`,
      );
    }

    const sorted = sortPageNames(names);
    const pages: ComicPage[] = sorted.map((name) => {
      throwIfAborted(options.signal);
      const content = entries[name];
      const mimeType = options.mimeType?.(name) ?? mimeTypeFor(name);
      const blob = new Blob([content], { type: mimeType });
      return {
        name,
        fileName: baseName(name),
        blob,
        url: createObjectUrl(blob),
        bytes: content.byteLength,
        mimeType,
      };
    });

    return new ZipProvider(pages, {
      totalEntries: Object.keys(entries).length,
      skippedEntries: Object.keys(entries).length - pages.length,
    });
  }

  /** Ordered pages; empty after {@link close}. */
  getPages(): ComicPage[] {
    return [...this.pages];
  }

  getPageCount(): number {
    return this.pages.length;
  }

  getPage(index: number): ComicPage | null {
    return this.pages[index] ?? null;
  }

  /** Page entry names only — enough for a reader UI without keeping blobs alive. */
  getPageNames(): string[] {
    return this.pages.map((page) => page.name);
  }

  getStats(): { totalEntries: number; skippedEntries: number; pageCount: number } {
    return { ...this.metadata, pageCount: this.pages.length };
  }

  isClosed(): boolean {
    return this.closed;
  }

  /** Revokes every object URL. Safe to call twice, but pages are unusable afterwards. */
  close(): void {
    if (this.closed) return;
    this.closed = true;
    for (const page of this.pages) {
      URL.revokeObjectURL(page.url);
    }
    this.pages.length = 0;
  }
}

/** Lists image entries without inflating them (nearly free; `unzip` keeps them in memory anyway). */
export async function listImageEntries(
  data: Uint8Array | ArrayBuffer,
): Promise<ZipEntryInfo[]> {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  const entries = await unzipAsync(bytes);
  return sortPageNames(Object.keys(entries).filter(isImageEntry)).map((name) => ({
    name,
    fileName: baseName(name),
    bytes: entries[name].byteLength,
    mimeType: mimeTypeFor(name),
  }));
}

function unzipAsync(bytes: Uint8Array): Promise<Unzipped> {
  return new Promise<Unzipped>((resolve, reject) => {
    unzip(bytes, (error, result) => {
      if (error) {
        reject(new Error(`Failed to read ZIP archive: ${error.message}`));
        return;
      }
      resolve(result);
    });
  });
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException("ZIP extraction aborted", "AbortError");
}
