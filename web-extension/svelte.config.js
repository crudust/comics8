import { vitePreprocess } from "@sveltejs/vite-plugin-svelte";

/**
 * Svelte compiler options.
 *
 * Only preprocessing lives here; Vite/CRXJS/Tailwind are configured in `vite.config.ts`. The file
 * exists because `svelte-check` (and editor integrations that do not read a Vite config) look for it
 * by convention.
 */
export default {
  preprocess: vitePreprocess(),
};
