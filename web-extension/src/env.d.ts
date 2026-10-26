/// <reference types="svelte" />
/// <reference types="vite/client" />
/// <reference types="chrome" />

/**
 * Ambient declarations for the extension's bundler-specific imports.
 *
 * `.svelte` files are typed by the Svelte language tools; the declaration below only keeps plain
 * `tsc` from erroring when a `.ts` entry point imports a component (the Svelte compiler and
 * `svelte-check` do the real work).
 *
 * `ImportMetaEnv`, `import.meta.env` and `import.meta.hot` all come from `vite/client`.
 */

declare module "*.svelte" {
  import type { Component } from "svelte";

  const component: Component<Record<string, unknown>>;
  export default component;
}

/** `?inline` stylesheet imports return the CSS text instead of registering a stylesheet. */
declare module "*.css?inline" {
  const css: string;
  export default css;
}
