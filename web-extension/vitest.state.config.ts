import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";

/**
 * Config for the ported desktop read model (`src/state/desktop-state.svelte.ts`).
 *
 * Like `vitest.views.config.ts`, this needs the Svelte plugin because rune modules
 * (`.svelte.ts`) are only valid after the compiler rewrites `$state` / `$derived`. It is a
 * separate config so the default suite stays a plain-Node logic suite. No DOM is required:
 * the store is state + pure rules, and the tests assert decisions, not rendering.
 *
 *   pnpm test:state
 */
export default defineConfig({
  plugins: [svelte()],
  resolve: { conditions: ["browser"] },
  ssr: { resolve: { conditions: ["browser"] }, externalConditions: ["browser"] },
  test: {
    environment: "node",
    include: ["tests/state-*.test.ts"],
  },
});
