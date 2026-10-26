/**
 * `host.parseHtml()` support: browser-DOM-backed equivalents of the Jsoup `HostHtmlDoc` /
 * `HostHtmlEl` surface documented in `docs/PLUGIN_GUIDE.md` §6.2.
 *
 * Two Jsoup behaviours are deliberately reproduced because plugins depend on them:
 *
 * 1. `text()` returns *rendered* text — inline elements are concatenated, block elements break the
 *    line — so `figcaption a` lookups do not glue neighbouring cells together.
 * 2. `absUrl(attr)` resolves against the document base (including a `<base href>`), never against
 *    the extension's own origin.
 */

const BLOCK_TAGS = new Set([
  "ADDRESS", "ARTICLE", "ASIDE", "BLOCKQUOTE", "DD", "DIV", "DL", "DT", "FIELDSET", "FIGCAPTION",
  "FIGURE", "FOOTER", "FORM", "H1", "H2", "H3", "H4", "H5", "H6", "HEADER", "HR", "LI", "MAIN",
  "NAV", "OL", "P", "PRE", "SECTION", "TABLE", "TBODY", "TD", "TFOOT", "TH", "THEAD", "TR", "UL",
]);

const SKIP_TEXT_TAGS = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE"]);

/** Priority order documented in §6.2 for `imgUrl()`. */
const IMAGE_ATTRS = ["data-src", "data-original", "data-lazy", "data-mobile-image", "src"] as const;

const BG_URL_RE = /url\(\s*(['"]?)(.+?)\1\s*\)/i;

const TEXT_NODE = 3;
const ELEMENT_NODE = 1;

export function parseHtmlDocument(html: string, baseUrl?: string | null): Document {
  const doc = new DOMParser().parseFromString(html ?? "", "text/html");
  if (baseUrl) {
    // Remember the caller's base without mutating the document: one HTML string may be re-parsed
    // against several bases.
    (doc as DocumentWithBase).__comics8Base = baseUrl;
  }
  return doc;
}

type DocumentWithBase = Document & { __comics8Base?: string };

function documentBase(doc: Document): string | null {
  const fromAttribute = (doc as DocumentWithBase).__comics8Base;
  if (fromAttribute) return fromAttribute;
  const href = doc.querySelector("base[href]")?.getAttribute("href")?.trim();
  if (!href) return null;
  try {
    return new URL(href, "https://base.invalid/").toString();
  } catch {
    return null;
  }
}

/**
 * Resolves `value` against `baseUrl`.
 *
 * Matches `HostApiV1Impl.resolveAbsUrl`: absolute URLs and `data:` URIs pass through, `//host`
 * becomes `https://host`, and anything else is joined to the base.
 */
export function resolveAbsUrl(value: string, baseUrl?: string | null): string {
  const raw = (value ?? "").trim().replace(/^['"]|['"]$/g, "");
  if (raw === "") return "";
  if (/^[a-z][a-z0-9+.-]*:/i.test(raw)) return raw;
  if (raw.startsWith("//")) return `https:${raw}`;
  if (!baseUrl) return raw;
  try {
    return new URL(raw, baseUrl).toString();
  } catch {
    const root = baseUrl.replace(/\/+$/, "");
    return raw.startsWith("/") ? `${root}${raw}` : `${root}/${raw}`;
  }
}

export function extractBackgroundUrl(style: string | null | undefined): string | null {
  if (!style) return null;
  return BG_URL_RE.exec(style)?.[2]?.replace(/^['"\s]+|['"\s]+$/g, "") ?? null;
}

/** Renders element text the way Jsoup's `Element.text()` does. */
export function renderedText(node: Node): string {
  const parts: string[] = [];

  const walk = (current: Node): void => {
    if (current.nodeType === TEXT_NODE) {
      parts.push(current.nodeValue ?? "");
      return;
    }
    if (current.nodeType !== ELEMENT_NODE) return;

    const el = current as Element;
    const tag = el.tagName.toUpperCase();
    if (SKIP_TEXT_TAGS.has(tag)) return;

    const isBlock = BLOCK_TAGS.has(tag);
    if (isBlock || tag === "BR") parts.push("\n");
    el.childNodes.forEach(walk);
    if (isBlock) parts.push("\n");
  };

  walk(node);
  return parts.join("").replace(/\s+/g, " ").trim();
}

export class HostHtmlEl {
  readonly element: Element;
  private readonly base: string | null;

  constructor(element: Element, base?: string | null) {
    this.element = element;
    this.base = base ?? null;
  }

  text(): string {
    return renderedText(this.element);
  }

  html(): string {
    return this.element.innerHTML;
  }

  attr(name: string): string {
    return this.element.getAttribute(name) ?? "";
  }

  absUrl(attrName: string): string {
    return resolveAbsUrl(this.attr(attrName), this.base);
  }

  select(css: string): HostHtmlEl[] {
    return Array.from(this.element.querySelectorAll(css)).map((el) => new HostHtmlEl(el, this.base));
  }

  selectFirst(css: string): HostHtmlEl | null {
    const found = this.element.querySelector(css);
    return found ? new HostHtmlEl(found, this.base) : null;
  }

  textOf(css: string): string {
    return this.selectFirst(css)?.text() ?? "";
  }

  attrOf(css: string, attrName: string): string {
    return this.selectFirst(css)?.attr(attrName) ?? "";
  }

  absUrlOf(css: string, attrName: string): string {
    return this.selectFirst(css)?.absUrl(attrName) ?? "";
  }

  bgUrl(): string | null {
    return extractBackgroundUrl(this.element.getAttribute("style"));
  }

  /** §6.2 — first of `data-src`, `data-original`, `data-lazy`, `data-mobile-image`, `src`, or CSS. */
  imgUrl(baseUrl?: string | null): string {
    const target =
      this.element.tagName.toUpperCase() === "IMG"
        ? this.element
        : this.element.querySelector("img") ?? this.element;

    let raw: string | null = null;
    for (const attrName of IMAGE_ATTRS) {
      if (target.hasAttribute(attrName)) {
        raw = target.getAttribute(attrName);
        break;
      }
    }
    if (!raw) raw = extractBackgroundUrl(target.getAttribute("style"));
    if (!raw) raw = extractBackgroundUrl(this.element.getAttribute("style"));

    const cleaned = (raw ?? "").trim().replace(/\\\//g, "/");
    if (!cleaned) return "";
    return resolveAbsUrl(cleaned, baseUrl ?? this.base);
  }

  imgUrlOf(css: string, baseUrl?: string | null): string {
    return this.selectFirst(css)?.imgUrl(baseUrl ?? this.base) ?? "";
  }
}

export class HostHtmlDoc {
  readonly document: Document;
  private readonly base: string | null;

  constructor(document: Document, baseUrl?: string | null) {
    this.document = document;
    this.base = baseUrl ?? documentBase(document);
  }

  select(css: string): HostHtmlEl[] {
    return Array.from(this.document.querySelectorAll(css)).map((el) => new HostHtmlEl(el, this.base));
  }

  selectFirst(css: string): HostHtmlEl | null {
    const found = this.document.querySelector(css);
    return found ? new HostHtmlEl(found, this.base) : null;
  }

  textOf(css: string): string {
    return this.selectFirst(css)?.text() ?? "";
  }

  attrOf(css: string, attrName: string): string {
    return this.selectFirst(css)?.attr(attrName) ?? "";
  }

  absUrlOf(css: string, attrName: string): string {
    return this.selectFirst(css)?.absUrl(attrName) ?? "";
  }

  imgUrlOf(css: string, baseUrl?: string | null): string {
    return this.selectFirst(css)?.imgUrl(baseUrl ?? this.base) ?? "";
  }

  text(): string {
    const root = this.document.body ?? this.document.documentElement;
    return root ? renderedText(root) : "";
  }

  html(): string {
    return this.document.documentElement?.outerHTML ?? "";
  }
}
