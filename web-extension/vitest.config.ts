import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    // `views-*.test.ts` mount Svelte components and need a DOM + the browser build of Svelte, so
    // they have their own config (`vitest.views.config.ts`, `pnpm test:views`).
    // `state-*.test.ts` needs the Svelte compiler for `.svelte.ts` runes, so it uses
    // `vitest.state.config.ts` (`pnpm test:state`); running it here would fail with
    // `$state is not defined`.
    include: ["tests/**/*.test.ts"],
    exclude: ["tests/views-*.test.ts", "tests/state-*.test.ts", "tests/ui-*.test.ts", "node_modules/**"],
  },
});
