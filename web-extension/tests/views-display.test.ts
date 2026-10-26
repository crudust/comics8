/**
 * Display logic for the Browse and Series views.
 *
 * The cases that matter are the ones a plugin can produce and a template cannot defend against:
 * a 0-based `currentPage`, a listing whose length is a slice rather than a total, a resume row
 * pointing at a chapter that is not on the loaded page, and a genre string with mixed separators.
 */

import { describe, expect, it } from "vitest";

import {
  dedupeEpisodes,
  dedupeSeries,
  episodeCountLabel,
  episodeNumber,
  isHttpUrl,
  normalisePageInfo,
  originHref,
  pageProgressLabel,
  resumeLabel,
  reverseEpisodes,
  seriesCardKey,
  splitTags,
} from "../src/views/display";
import type { EpisodeItem, ToonItem } from "../src/sources/types";

const episode = (wrId: string, title = `${wrId}화 제목`): EpisodeItem => ({
  wrId,
  title,
  href: `https://src.test/${wrId}`,
});

const singlePage = { currentPage: 1, lastPage: 1 };
/** Newest first, as every bundled plugin returns them. */
const newestFirst = [episode("e3"), episode("e2"), episode("e1")];

const toon = (overrides: Partial<ToonItem> = {}): ToonItem => ({
  id: "42",
  title: "작품",
  thumbUrl: "",
  href: "https://src.test/42",
  ...overrides,
});

describe("splitTags", () => {
  it("splits the separator styles plugins actually use", () => {
    expect(splitTags("Webcomic, Fantasy (CC BY 4.0)")).toEqual(["Webcomic", "Fantasy (CC BY 4.0)"]);
    expect(splitTags("액션/판타지")).toEqual(["액션", "판타지"]);
    expect(splitTags("연재·일상")).toEqual(["연재", "일상"]);
    expect(splitTags("드라마 | 스릴러")).toEqual(["드라마", "스릴러"]);
  });

  it("drops empty parts and clamps to the card limit", () => {
    expect(splitTags("  , , ")).toEqual([]);
    expect(splitTags(null)).toEqual([]);
    expect(splitTags("")).toEqual([]);
    expect(splitTags("a,b,c,d,e")).toHaveLength(3);
    expect(splitTags("a,b,c,d,e", 4)).toHaveLength(4);
    expect(splitTags("a,b,c", 0)).toEqual([]);
  });
});

describe("normalisePageInfo", () => {
  it("keeps a valid page range", () => {
    expect(normalisePageInfo({ currentPage: 3, lastPage: 9 }, 1)).toEqual({
      currentPage: 3,
      lastPage: 9,
    });
  });

  it("falls back to the requested page when pageInfo is missing", () => {
    expect(normalisePageInfo(undefined, 4)).toEqual({ currentPage: 4, lastPage: 4 });
  });

  it("repairs a 0/negative current page and a current page past the end", () => {
    expect(normalisePageInfo({ currentPage: 0, lastPage: 5 }, 1)).toEqual({
      currentPage: 1,
      lastPage: 5,
    });
    expect(normalisePageInfo({ currentPage: 9, lastPage: 2 }, 1)).toEqual({
      currentPage: 9,
      lastPage: 9,
    });
  });

  it("never reports lastPage below currentPage", () => {
    const info = normalisePageInfo({ currentPage: 7, lastPage: 0 }, 1);
    expect(info.lastPage).toBeGreaterThanOrEqual(info.currentPage);
  });
});

describe("episodeCountLabel", () => {
  it("counts exactly when the whole listing fits on one page", () => {
    expect(episodeCountLabel(3, singlePage)).toBe("총 3화");
  });

  it("never multiplies a paginated listing into a fake total", () => {
    const label = episodeCountLabel(100, { currentPage: 2, lastPage: 7 });
    expect(label).toBe("100화 표시 중 · 2/7 페이지");
    expect(label).not.toContain("700");
  });
});

describe("episodeNumber", () => {
  it("numbers from the oldest chapter on a single-page listing", () => {
    expect(episodeNumber(newestFirst[0], newestFirst, singlePage)).toBe(3);
    expect(episodeNumber(newestFirst[1], newestFirst, singlePage)).toBe(2);
    expect(episodeNumber(newestFirst[2], newestFirst, singlePage)).toBe(1);
  });

  it("refuses to number a slice of a paginated listing", () => {
    expect(episodeNumber(newestFirst[0], newestFirst, { currentPage: 2, lastPage: 5 })).toBeNull();
  });

  it("returns null for an episode that is not in the list", () => {
    expect(episodeNumber(episode("nope"), newestFirst, singlePage)).toBeNull();
  });
});

describe("resumeLabel", () => {
  const target = (overrides = {}) => ({
    lastWrId: "e2",
    lastEpisodeTitle: "2화 제목",
    pageIndex: 0,
    totalPages: 0,
    ...overrides,
  });

  it("shows the chapter position and 1-based page progress", () => {
    expect(resumeLabel(target({ pageIndex: 14, totalPages: 45 }), newestFirst, singlePage)).toBe(
      "2화 이어보기 (15/45p)",
    );
  });

  it("omits the page part when the chapter has not been turned past page 1", () => {
    expect(resumeLabel(target({ pageIndex: 0, totalPages: 45 }), newestFirst, singlePage)).toBe(
      "2화 이어보기",
    );
  });

  it("falls back to the stored title when the chapter is on another listing page", () => {
    const label = resumeLabel(
      target({ lastWrId: "gone", lastEpisodeTitle: "12화 최종화", pageIndex: 4, totalPages: 0 }),
      newestFirst,
      { currentPage: 2, lastPage: 5 },
    );
    expect(label).toBe("12화 최종화 이어보기 (5p)");
  });

  it("never reports a page past the end of the chapter", () => {
    expect(resumeLabel(target({ pageIndex: 99, totalPages: 45 }), newestFirst, singlePage)).toBe(
      "2화 이어보기 (45/45p)",
    );
  });

  it("survives a history row without a title", () => {
    expect(
      resumeLabel(target({ lastEpisodeTitle: "", lastWrId: "gone" }), newestFirst, singlePage),
    ).toBe("마지막 회차 이어보기");
  });
});

describe("pageProgressLabel", () => {
  it("renders 1-based progress and stays quiet at the first page", () => {
    expect(pageProgressLabel(14, 45)).toBe("15/45p");
    expect(pageProgressLabel(0, 45)).toBeNull();
    expect(pageProgressLabel(0, 0)).toBeNull();
    expect(pageProgressLabel(3, 0)).toBe("4p");
  });
});

describe("originHref", () => {
  it("prefers the series URL, then the source origin", () => {
    expect(originHref("https://src.test/42", "https://src.test")).toBe("https://src.test/42");
    expect(originHref("", "https://src.test")).toBe("https://src.test");
    expect(originHref(null, "https://src.test/")).toBe("https://src.test/");
  });

  it("refuses anything that is not an absolute http(s) URL", () => {
    expect(originHref("/relative", "javascript:alert(1)")).toBe("");
    expect(originHref("", "")).toBe("");
    expect(isHttpUrl("data:text/html,x")).toBe(false);
    expect(isHttpUrl("https://ok.test")).toBe(true);
  });
});

describe("dedupe and keys", () => {
  it("drops repeated episodes while keeping order", () => {
    const list = [episode("e2"), episode("e1"), episode("e2")];
    expect(dedupeEpisodes(list).map((entry) => entry.wrId)).toEqual(["e2", "e1"]);
  });

  it("drops episodes with no identity at all", () => {
    expect(dedupeEpisodes([{ wrId: "", title: "x", href: "" }])).toEqual([]);
  });

  it("treats the same id with a different entry episode as a separate card", () => {
    const oneShotA = toon({ entryEpisodeId: "a" });
    const oneShotB = toon({ entryEpisodeId: "b" });
    expect(seriesCardKey(oneShotA)).not.toBe(seriesCardKey(oneShotB));
    expect(dedupeSeries([oneShotA, oneShotB])).toHaveLength(2);
    expect(dedupeSeries([oneShotA, toon({ entryEpisodeId: "a" })])).toHaveLength(1);
  });

  it("reverses without mutating the source array", () => {
    const list = [episode("e2"), episode("e1")];
    const reversed = reverseEpisodes(list);
    expect(reversed.map((entry) => entry.wrId)).toEqual(["e1", "e2"]);
    expect(list.map((entry) => entry.wrId)).toEqual(["e2", "e1"]);
  });
});
