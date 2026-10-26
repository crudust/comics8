import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";

/**
 * Config for the component tests in `tests/views-*.test.ts`.
 *
 * Kept out of `vitest.config.ts` on purpose: the default suite runs pure logic under Node, while
 * these need a DOM and Svelte's *client* build. Two separate configs keep the fast suite fast and
 * avoid forcing every consumer of the default config to resolve browser conditions.
 *
 *   pnpm test:views
 */
export default defineConfig({
  plugins: [svelte()],
  resolve: { conditions: ["browser"] },
  ssr: { resolve: { conditions: ["browser"] }, externalConditions: ["browser"] },
  test: {
    environment: "happy-dom",
    include: [
      "tests/views-behavior.test.ts",
      "tests/views-reader-app.test.ts",
      "tests/views-series-pane.test.ts",
      "tests/views-browse-history-pane.test.ts",
      "tests/views-reader-pane.test.ts",
      "tests/ui-topbar.test.ts",
      "tests/ui-sync-dialog.test.ts",
      "tests/views-source-manager-pane.test.ts",
      "tests/views-settings-pane.test.ts",
    ],
  },
});
