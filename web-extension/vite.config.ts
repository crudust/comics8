import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import tailwindcss from "@tailwindcss/vite";
import { crx } from "@crxjs/vite-plugin";
import manifest from "./manifest.json";

export default defineConfig({
  plugins: [
    // `tailwindcss()` must run before the Svelte plugin so utility classes referenced in
    // `<script>`-generated markup are still picked up by the scanner.
    tailwindcss(),
    svelte(),
    // Reads `manifest.json`, derives every entry (service worker, reader page, sandbox) from it and
    // rewrites the emitted manifest to point at the built files.
    crx({ manifest }),
  ],
  build: {
    target: "chrome116",
    outDir: "dist",
    emptyOutDir: true,
    // CRXJS derives its entry points from the manifest, and the action has no `default_popup` /
    // `side_panel` anymore (it opens the reader through `chrome.action.onClicked`), so the reader
    // page has to be named explicitly or it would never be emitted.
    rollupOptions: {
      input: { reader: "src/index.html" },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    // The Chrome extension connects to the dev server from a `chrome-extension://` origin.
    cors: true,
    hmr: { port: 5173 },
  },
  clearScreen: false,
});
