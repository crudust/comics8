/**
 * Entry point for the reader surface (`src/index.html`), which the toolbar icon opens in a tab.
 *
 * Mounts the Svelte shell on `#app`. The shell is intentionally independent of any source or storage
 * backend, so this file stays a small bootstrap: wiring a chapter into `viewerState` is all a caller
 * needs to render it.
 */

import { mount, unmount } from "svelte";

import "./app.css";
import ReaderApp from "./ReaderApp.svelte";

const target = document.getElementById("app");
if (!target) {
  throw new Error("Comics8: #app is missing from src/index.html");
}

const app = mount(ReaderApp, { target });

// Vite HMR would otherwise stack a second instance on the same element.
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    void unmount(app);
  });
}

export default app;
