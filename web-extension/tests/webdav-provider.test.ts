/**
 * WebDAV provider: PROPFIND envelope, `D:multistatus` parsing, auth retry, binary fetch paths.
 */

import { afterEach, describe, expect, it } from "vitest";
import { WebDavProvider, normalizePath, parseMultiStatus } from "../src/storage/webdav-provider";
import { installDom } from "./harness";

/** Node-safe base64 (the extension itself encodes in the service worker). */
function toBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

installDom();

type Recorded = {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
  responseType?: string;
};

/** Installs a fake `chrome.runtime.sendMessage` and returns the recorded requests. */
function stubChrome(
  respond: (request: Recorded) => {
    status?: number;
    body?: string;
    bodyBase64?: string;
    headers?: Record<string, string>;
    error?: string;
  },
): Recorded[] {
  const recorded: Recorded[] = [];
  const sendMessage = async (message: {
    requestId: string;
    url: string;
    method: string;
    headers: Record<string, string>;
    body?: string;
    responseType?: string;
  }) => {
    const request: Recorded = {
      url: message.url,
      method: message.method,
      headers: message.headers,
      body: message.body,
      responseType: message.responseType,
    };
    recorded.push(request);
    const result = respond(request);
    const status = result.status ?? 200;
    return {
      action: "FETCH_RESPONSE",
      requestId: message.requestId,
      ok: status >= 200 && status < 300,
      status,
      statusText: "",
      url: message.url,
      headers: result.headers ?? {},
      body: result.bodyBase64 ? "" : (result.body ?? ""),
      bodyBase64: result.bodyBase64,
      error: result.error,
    };
  };
  (globalThis as unknown as { chrome: unknown }).chrome = { runtime: { sendMessage } };
  return recorded;
}

afterEach(() => {
  delete (globalThis as unknown as { chrome?: unknown }).chrome;
});

const MULTISTATUS = `<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/comics/</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/comics/Series%20A/</D:href>
    <D:propstat>
      <D:prop><D:displayname>Series A</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/comics/Series%20A/chapter%2001.cbz</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>2048</D:getcontentlength>
        <D:getlastmodified>Sun, 06 Nov 1994 08:49:37 GMT</D:getlastmodified>
        <D:getetag>"abc123"</D:getetag>
        <D:getcontenttype>application/vnd.comicbook+zip</D:getcontenttype>
        <D:resourcetype/>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/comics/Series%20A/001.jpg</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>512</D:getcontentlength>
        <D:getcontenttype>image/jpeg</D:getcontenttype>
        <D:resourcetype/>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/comics/notes.txt</D:href>
    <D:propstat>
      <D:prop><D:getcontentlength>10</D:getcontentlength><D:resourcetype/></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>`;

describe("parseMultiStatus", () => {
  it("extracts folders, archives, images and other files", () => {
    const entries = parseMultiStatus(MULTISTATUS, "comics");
    const byName = Object.fromEntries(entries.map((entry) => [entry.name, entry]));

    expect(byName["Series A"]).toMatchObject({
      // Paths are relative to the configured root, like `NetworkNode.path` in the Kotlin client.
      path: "Series A",
      directory: true,
      kind: "directory",
    });
    expect(byName["chapter 01.cbz"]).toMatchObject({
      path: "Series A/chapter 01.cbz",
      directory: false,
      kind: "archive",
      sizeBytes: 2048,
      etag: '"abc123"',
      contentType: "application/vnd.comicbook+zip",
    });
    expect(byName["chapter 01.cbz"].lastModified).toBe(Date.parse("Sun, 06 Nov 1994 08:49:37 GMT"));
    expect(byName["001.jpg"]).toMatchObject({ kind: "image", sizeBytes: 512 });
    expect(byName["notes.txt"]).toMatchObject({ kind: "other" });
  });

  it("does not emit the requested collection itself", () => {
    const entries = parseMultiStatus(MULTISTATUS, "comics");
    expect(entries.some((entry) => entry.path === "comics")).toBe(false);
    expect(entries).toHaveLength(4);
  });

  it("sorts directories first, then names numerically", () => {
    const entries = parseMultiStatus(
      `<D:multistatus xmlns:D="DAV:">
        <D:response><D:href>/r/10.jpg</D:href><D:propstat><D:prop/></D:propstat></D:response>
        <D:response><D:href>/r/2.jpg</D:href><D:propstat><D:prop/></D:propstat></D:response>
        <D:response><D:href>/r/zeta/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
      </D:multistatus>`,
      "r",
    );
    expect(entries.map((entry) => entry.name)).toEqual(["zeta", "2.jpg", "10.jpg"]);
  });

  it("rejects DOCTYPE responses (XXE / entity expansion defence)", () => {
    const hostile = `<?xml version="1.0"?>
      <!DOCTYPE d [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
      <D:multistatus xmlns:D="DAV:"><D:response><D:href>/a&xxe;</D:href></D:response></D:multistatus>`;
    expect(() => parseMultiStatus(hostile)).toThrow(/DOCTYPE/);
  });

  it("throws on malformed XML rather than returning an empty listing", () => {
    expect(() => parseMultiStatus("<D:multistatus><D:response>")).toThrow();
  });

  it("tolerates servers that omit the DAV namespace", () => {
    const entries = parseMultiStatus(
      `<multistatus><response><href>/r/a.cbz</href><propstat><prop>
         <getcontentlength>99</getcontentlength></prop></propstat></response></multistatus>`,
      "r",
    );
    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({ name: "a.cbz", kind: "archive", sizeBytes: 99 });
  });
});

describe("normalizePath", () => {
  it("strips leading/trailing slashes and collapses dot segments", () => {
    expect(normalizePath("/a/b/")).toBe("a/b");
    expect(normalizePath("a//b")).toBe("a/b");
    expect(normalizePath("./a/./b")).toBe("a/b");
    expect(normalizePath("")).toBe("");
    expect(normalizePath(null)).toBe("");
  });
});

describe("WebDavProvider", () => {
  it("validates the URL scheme", () => {
    expect(() => new WebDavProvider({ url: "dav.example.com" })).toThrow(/http:\/\/ or https:\/\//);
  });

  it("sends PROPFIND with Depth: 1 and the documented body", async () => {
    const requests = stubChrome(() => ({ status: 207, body: MULTISTATUS }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    await provider.list("");

    expect(requests).toHaveLength(1);
    expect(requests[0].method).toBe("PROPFIND");
    expect(requests[0].url).toBe("https://dav.test/comics/");
    expect(requests[0].headers.Depth).toBe("1");
    expect(requests[0].headers["Content-Type"]).toContain("application/xml");
    expect(requests[0].body).toContain("<d:propfind");
    expect(requests[0].body).toContain("<d:getlastmodified/>");
  });

  it("percent-encodes each path segment", async () => {
    const requests = stubChrome(() => ({ status: 207, body: "<D:multistatus xmlns:D=\"DAV:\"/>" }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics/" });
    await provider.list("Series A/chapter 01");
    expect(requests[0].url).toBe("https://dav.test/comics/Series%20A/chapter%2001/");
  });

  it("caches listings until invalidated", async () => {
    const requests = stubChrome(() => ({ status: 207, body: MULTISTATUS }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });

    await provider.list("");
    await provider.list("");
    expect(requests).toHaveLength(1);

    await provider.list("", { refresh: true });
    expect(requests).toHaveLength(2);

    await provider.list("");
    provider.invalidateCache();
    await provider.list("");
    // 1 miss, 1 hit, 1 refresh, 1 hit, then 1 re-fetch after invalidation.
    expect(requests).toHaveLength(3);
  });

  it("filters directories, archives and images", async () => {
    stubChrome(() => ({ status: 207, body: MULTISTATUS }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });

    await expect(provider.listDirectories("")).resolves.toHaveLength(1);
    await expect(provider.listArchives("")).resolves.toHaveLength(1);
    const images = await provider.listImages("");
    expect(images.map((entry) => entry.name)).toEqual(["001.jpg"]);
  });

  it("classifies by content-type when the extension is missing", () => {
    const entries = parseMultiStatus(
      `<D:multistatus xmlns:D="DAV:"><D:response><D:href>/r/scan</D:href><D:propstat><D:prop>
         <D:getcontenttype>image/png</D:getcontenttype></D:prop></D:propstat></D:response></D:multistatus>`,
      "r",
    );
    expect(entries[0].kind).toBe("image");
  });

  it("retries once with Basic auth when the server answers 401", async () => {
    let calls = 0;
    const requests = stubChrome(() => {
      calls += 1;
      return calls === 1
        ? { status: 401, body: "unauthorized" }
        : { status: 207, body: MULTISTATUS };
    });

    const provider = new WebDavProvider({
      url: "https://dav.test/comics",
      username: "user",
      password: "p@ss:word",
    });
    const entries = await provider.list("");
    expect(entries.length).toBeGreaterThan(0);
    expect(requests).toHaveLength(2);
    expect(requests[1].headers.Authorization).toBe(`Basic ${btoa("user:p@ss:word")}`);
  });

  it("does not retry with auth when no credentials are configured", async () => {
    const requests = stubChrome(() => ({ status: 401, body: "" }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    await expect(provider.list("")).rejects.toThrow(/access denied \(HTTP 401\)/);
    expect(requests).toHaveLength(1);
  });

  it("reports missing paths with 404 semantics", async () => {
    stubChrome(() => ({ status: 404, body: "" }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    await expect(provider.list("nope")).rejects.toThrow(/not found: nope/);
  });

  it("surfaces transport errors with method and URL context", async () => {
    stubChrome(() => ({ error: "net::ERR_NAME_NOT_RESOLVED" }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    await expect(provider.list("")).rejects.toThrow(/PROPFIND https:\/\/dav\.test\/comics\/.*ERR_NAME_NOT_RESOLVED/);
  });

  it("downloads files as bytes through the base64 channel", async () => {
    const payload = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0xff, 0x00]);
    const requests = stubChrome(() => ({
      status: 200,
      bodyBase64: toBase64(payload),
    }));

    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    const bytes = await provider.download("Series A/chapter 01.cbz");
    expect([...bytes]).toEqual([...payload]);
    expect(requests[0].responseType).toBe("base64");
    expect(requests[0].method).toBe("GET");
  });

  it("rejects failed downloads rather than returning partial bytes", async () => {
    stubChrome(() => ({ status: 500, body: "" }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    await expect(provider.download("a.cbz")).rejects.toThrow(/download failed \(HTTP 500\)/);
  });

  it("turns an authenticated image into a blob URL that can be revoked", async () => {
    const bytes = new Uint8Array([1, 2, 3]);
    stubChrome(() => ({
      status: 200,
      bodyBase64: toBase64(bytes),
      headers: { "content-type": "image/jpeg" },
    }));

    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    const url = await provider.fetchImage("001.jpg");
    expect(url.startsWith("blob:")).toBe(true);
    expect(() => provider.releaseBlobUrl(url)).not.toThrow();
    // Non-blob URLs are left alone.
    expect(() => provider.releaseBlobUrl("https://dav.test/x.jpg")).not.toThrow();
  });

  it("resolves an image folder into ordered pages with absolute URLs", async () => {
    stubChrome(() => ({
      status: 207,
      body: `<D:multistatus xmlns:D="DAV:">
        <D:response><D:href>/comics/ch/10.jpg</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
        <D:response><D:href>/comics/ch/2.jpg</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
        <D:response><D:href>/comics/ch/1.jpg</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
      </D:multistatus>`,
    }));

    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    const dir = await provider.openImageDirectory("ch");
    expect(dir.path).toBe("ch");
    expect(dir.pages.map((page) => page.name)).toEqual(["1.jpg", "2.jpg", "10.jpg"]);
    expect(dir.pages[0].url).toBe("https://dav.test/comics/ch/1.jpg");
  });

  it("throws when an image folder is empty", async () => {
    stubChrome(() => ({ status: 207, body: '<D:multistatus xmlns:D="DAV:"/>' }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    await expect(provider.openImageDirectory("empty")).rejects.toThrow(/No images found/);
  });

  it("test() verifies credentials by listing the root", async () => {
    const requests = stubChrome(() => ({ status: 207, body: MULTISTATUS }));
    const provider = new WebDavProvider({ url: "https://dav.test/comics" });
    await expect(provider.test()).resolves.toHaveLength(4);
    expect(requests[0].url).toBe("https://dav.test/comics/");
  });

  it("passes custom headers on every request", async () => {
    const requests = stubChrome(() => ({ status: 207, body: MULTISTATUS }));
    const provider = new WebDavProvider({
      url: "https://dav.test/comics",
      headers: { "User-Agent": "Comics8-extension" },
    });
    await provider.list("");
    expect(requests[0].headers["User-Agent"]).toBe("Comics8-extension");
  });
});
