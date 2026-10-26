/**
 * Test harness: installs a browser-like `DOMParser` (HTML via linkedom, XML via @xmldom/xmldom) and
 * `Node`/blob-URL shims on `globalThis`, since the extension code targets a real browser.
 */

import { DOMParser as LinkeDOMDOMParser, Node as LinkeDOMNode } from "linkedom";
import { DOMParser as XmlDOMParser } from "@xmldom/xmldom";

/** Absolute-path shim: extension code assumes a page origin when resolving relative URLs. */
const DUMMY_BASE = "https://example.test/";

class TestDOMParser {
  parseFromString(source: string, mimeType: string): Document {
    if (mimeType.includes("xml")) {
      const doc = new XmlDOMParser({
        onError: (level: string, message: string) => {
          if (level === "error" || level === "fatalError") {
            throw new Error(message);
          }
        },
      }).parseFromString(source, mimeType as "text/xml") as unknown as Document;
      // @xmldom never produces a parsererror element, so emulate the browser contract.
      return doc;
    }

    const doc = new LinkeDOMDOMParser().parseFromString(
      source,
      "text/html",
    ) as unknown as Document;
    // linkedom does not resolve hrefs itself; extension code resolves via URL instead.
    void DUMMY_BASE;
    return doc;
  }
}

const objectUrls = new Map<string, Blob>();

export function installDom(): void {
  const global = globalThis as unknown as Record<string, unknown>;
  global.DOMParser = TestDOMParser;
  // Reuse linkedom's own Node so `instanceof` checks inside its DOM keep working.
  global.Node = LinkeDOMNode;

  // Object URL shim so ZIP pages can be asserted and revocations observed. Node's native
  // implementation keeps its own opaque registry, so it is always replaced.
  const url = global.URL as unknown as typeof URL;
  url.createObjectURL = (blob: Blob): string => {
    const id = `blob:test/${objectUrls.size + 1}`;
    objectUrls.set(id, blob);
    return id;
  };
  url.revokeObjectURL = (value: string): void => {
    objectUrls.delete(value);
  };
}

export function liveObjectUrls(): string[] {
  return [...objectUrls.keys()];
}

export function resetObjectUrls(): void {
  objectUrls.clear();
}

installDom();
