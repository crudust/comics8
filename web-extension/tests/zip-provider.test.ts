/**
 * ZIP / CBZ provider: entry filtering, natural ordering, object-URL lifecycle.
 */

import { describe, expect, it } from "vitest";
import { zipSync, strToU8 } from "fflate";
import {
  ZipProvider,
  archiveExtension,
  baseName,
  isArchiveName,
  isImageEntry,
  isJunkEntry,
  listImageEntries,
  mimeTypeFor,
  naturalCompare,
  sortPageNames,
} from "../src/storage/zip-provider";
import { installDom, liveObjectUrls, resetObjectUrls } from "./harness";

installDom();

/** Builds a deflate-able ZIP from `name -> contents` pairs. */
function makeZip(entries: Record<string, Uint8Array | string>): Uint8Array {
  const files: Record<string, Uint8Array> = {};
  for (const [name, value] of Object.entries(entries)) {
    files[name] = typeof value === "string" ? strToU8(value) : value;
  }
  return zipSync(files);
}

/** Tiny GIF/PNG-ish byte payload; contents never matter to the provider. */
const PIXEL = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);

describe("ZIP entry classification", () => {
  it("accepts the documented image extensions and the archive extensions", () => {
    for (const ext of ["jpg", "jpeg", "png", "webp", "avif", "gif"]) {
      expect(isImageEntry(`page.${ext}`)).toBe(true);
      expect(isImageEntry(`page.${ext.toUpperCase()}`)).toBe(true);
    }
    expect(isImageEntry("notes.txt")).toBe(false);
    expect(isImageEntry("chapter.cbz")).toBe(false);

    expect(archiveExtension("a.cbz")).toBe("cbz");
    expect(archiveExtension("a.ZIP")).toBe("zip");
    expect(archiveExtension("a.rar")).toBeNull();
    expect(isArchiveName("a.cbz")).toBe(true);
    expect(isArchiveName("folder/")).toBe(false);
  });

  it("drops __MACOSX, dotfiles and directory entries", () => {
    expect(isJunkEntry("__MACOSX/._page.jpg")).toBe(true);
    expect(isJunkEntry("__MACOSX/page.jpg")).toBe(true);
    expect(isJunkEntry(".DS_Store")).toBe(true);
    expect(isJunkEntry("chapter/.hidden.jpg")).toBe(true);
    expect(isJunkEntry("chapter/page.jpg")).toBe(false);
    expect(isImageEntry("chapter/")).toBe(false);
  });

  it("maps extensions to MIME types", () => {
    expect(mimeTypeFor("a.jpg")).toBe("image/jpeg");
    expect(mimeTypeFor("a.jpeg")).toBe("image/jpeg");
    expect(mimeTypeFor("a.avif")).toBe("image/avif");
    expect(mimeTypeFor("a.unknown")).toBe("application/octet-stream");
    expect(baseName("chapter/01.jpg")).toBe("01.jpg");
    expect(baseName("01.jpg")).toBe("01.jpg");
  });
});

describe("natural ordering", () => {
  it("orders digits numerically", () => {
    const names = ["10.jpg", "2.jpg", "1.jpg", "20.jpg", "3.jpg"];
    expect(sortPageNames(names)).toEqual(["1.jpg", "2.jpg", "3.jpg", "10.jpg", "20.jpg"]);
  });

  it("puts zero-padded runs after the same unpadded value, like core NaturalSort", () => {
    expect(naturalCompare("1.jpg", "01.jpg")).toBeLessThan(0);
    expect(naturalCompare("01.jpg", "001.jpg")).toBeLessThan(0);
    expect(naturalCompare("2.jpg", "02.jpg")).toBeLessThan(0);
  });

  it("orders case-insensitively but keeps a deterministic tiebreak", () => {
    // Numeric comparison dominates, so 2 sorts before 10 regardless of case.
    expect(naturalCompare("Page2.jpg", "page10.jpg")).toBeLessThan(0);
    // Equal ignoring case falls back to code-point order, like NaturalSort's last tiebreak.
    expect(naturalCompare("PAGE1.jpg", "page1.jpg")).toBeLessThan(0);
    expect(naturalCompare("page1.jpg", "PAGE1.jpg")).toBeGreaterThan(0);
    expect(naturalCompare("a.jpg", "a.jpg")).toBe(0);
  });

  it("sorts mixed chapter paths deterministically", () => {
    const names = ["ch10/p10.jpg", "ch2/p1.jpg", "ch1/p1.jpg", "ch2/p10.jpg", "ch1/p2.jpg"];
    expect(sortPageNames(names)).toEqual([
      "ch1/p1.jpg",
      "ch1/p2.jpg",
      "ch2/p1.jpg",
      "ch2/p10.jpg",
      "ch10/p10.jpg",
    ]);
  });

  it("does not mutate the input", () => {
    const names = ["b.jpg", "a.jpg"];
    sortPageNames(names);
    expect(names).toEqual(["b.jpg", "a.jpg"]);
  });
});

describe("ZipProvider", () => {
  it("extracts only images, in natural order, and exposes object URLs", async () => {
    resetObjectUrls();
    const zip = makeZip({
      "chapter/1.jpg": PIXEL,
      "chapter/10.jpg": PIXEL,
      "chapter/2.jpg": PIXEL,
      "chapter/cover.PNG": PIXEL,
      "chapter/notes.txt": "ignored",
      "chapter/": new Uint8Array(0),
      "__MACOSX/._1.jpg": PIXEL,
      ".DS_Store": "junk",
    });

    const provider = await ZipProvider.open(zip);
    // Digits sort as numbers, so 2 precedes 10; the non-digit run decides before the extension.
    expect(provider.getPageNames()).toEqual([
      "chapter/1.jpg",
      "chapter/2.jpg",
      "chapter/10.jpg",
      "chapter/cover.PNG",
    ]);
    expect(provider.getPageCount()).toBe(4);
    expect(provider.getStats()).toMatchObject({ pageCount: 4 });

    const page = provider.getPage(0);
    expect(page?.url.startsWith("blob:")).toBe(true);
    expect(page?.mimeType).toBe("image/jpeg");
    expect(page?.fileName).toBe("1.jpg");
    expect(page?.bytes).toBe(PIXEL.byteLength);
    expect(await page?.blob.arrayBuffer()).toBeInstanceOf(ArrayBuffer);
    expect(liveObjectUrls()).toHaveLength(4);

    provider.close();
  });

  it("assigns MIME types by extension", async () => {
    const provider = await ZipProvider.open(
      makeZip({ "1.jpg": PIXEL, "2.webp": PIXEL, "3.avif": PIXEL, "4.gif": PIXEL }),
    );
    expect(provider.getPages().map((p) => p.mimeType)).toEqual([
      "image/jpeg",
      "image/webp",
      "image/avif",
      "image/gif",
    ]);
    provider.close();
  });

  it("honours a caller-supplied MIME override", async () => {
    const provider = await ZipProvider.open(makeZip({ "1.jpg": PIXEL }), {
      mimeType: () => "image/custom",
    });
    expect(provider.getPage(0)?.mimeType).toBe("image/custom");
    provider.close();
  });

  it("revokes every object URL on close and is idempotent", async () => {
    resetObjectUrls();
    const provider = await ZipProvider.open(makeZip({ "1.jpg": PIXEL, "2.jpg": PIXEL }));
    expect(liveObjectUrls()).toHaveLength(2);

    provider.close();
    expect(liveObjectUrls()).toHaveLength(0);
    expect(provider.getPageCount()).toBe(0);
    expect(provider.isClosed()).toBe(true);

    provider.close();
    expect(liveObjectUrls()).toHaveLength(0);
  });

  it("rejects an archive with no images, naming the skipped entries", async () => {
    await expect(ZipProvider.open(makeZip({ "readme.txt": "x", "info.xml": "<a/>" }))).rejects.toThrow(
      /no comic images \(2 non-image entries found\)/,
    );
  });

  it("rejects empty input", async () => {
    await expect(ZipProvider.open(new Uint8Array(0))).rejects.toThrow("ZIP archive is empty");
  });

  it("reports corrupt archives as a ZIP error", async () => {
    await expect(ZipProvider.open(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]))).rejects.toThrow(
      /Failed to read ZIP archive/,
    );
  });

  it("accepts an ArrayBuffer as well as a Uint8Array", async () => {
    const zip = makeZip({ "1.jpg": PIXEL });
    const provider = await ZipProvider.open(zip.buffer.slice(0) as ArrayBuffer);
    expect(provider.getPageCount()).toBe(1);
    provider.close();
  });

  it("supports aborting before extraction finishes", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(
      ZipProvider.open(makeZip({ "1.jpg": PIXEL }), { signal: controller.signal }),
    ).rejects.toThrow(/aborted/);
  });

  it("lists entries with sizes without creating object URLs", async () => {
    resetObjectUrls();
    const entries = await listImageEntries(makeZip({ "10.jpg": PIXEL, "2.jpg": PIXEL }));
    expect(entries.map((e) => e.name)).toEqual(["2.jpg", "10.jpg"]);
    expect(entries[0].bytes).toBe(PIXEL.byteLength);
    expect(liveObjectUrls()).toHaveLength(0);
  });
});
