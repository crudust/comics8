/**
 * Toolbar action handler: the extension icon opens the reader as a full page.
 *
 * There is no popup and no side panel. `chrome.action.onClicked` only fires when the action has no
 * `default_popup`, which is why `manifest.json` keeps the action down to a title.
 *
 * Clicking the icon focuses the reader tab when one is already open, otherwise it opens
 * {@link READER_PAGE_PATH} in a new tab, so repeated clicks never pile up duplicate readers.
 */

/** Page the action opens; also the only surface of the extension. */
export const READER_PAGE_PATH = "src/index.html";

/** The slice of `chrome.tabs` this handler needs (`query` / `update` / `create`). */
export type TabsApi = Pick<typeof chrome.tabs, "query" | "create" | "update">;

/**
 * Focuses the existing reader tab, or opens one.
 *
 * Tabs are matched by URL rather than through `tabs.query({ url })`: that filter needs the `tabs`
 * permission, while the extension can always see its own pages.
 *
 * @param url  absolute reader URL, normally `chrome.runtime.getURL(READER_PAGE_PATH)`.
 * @param tabs `chrome.tabs` in the worker; injectable for tests.
 */
export async function focusOrOpenReader(url: string, tabs: TabsApi): Promise<void> {
  const stale = await tabs.query({});
  const existing = stale.find((tab) => typeof tab.url === "string" && tab.url.startsWith(url));
  if (existing?.id != null) {
    await tabs.update(existing.id, { active: true });
    return;
  }
  await tabs.create({ url });
}

/**
 * Installs the `chrome.action.onClicked` listener that opens the reader tab.
 *
 * Returns a disposer so development HMR (and tests) can detach it again, matching the other
 * background handlers in this folder.
 */
export function installActionHandler(
  url: string = chrome.runtime.getURL(READER_PAGE_PATH),
): () => void {
  const onClicked = chrome.action?.onClicked;
  if (!onClicked) return () => {};

  const listener = (): void => {
    void focusOrOpenReader(url, chrome.tabs).catch((error: unknown) => {
      console.error("[comics8] could not open the reader tab", error);
    });
  };

  onClicked.addListener(listener);
  return () => onClicked.removeListener(listener);
}
