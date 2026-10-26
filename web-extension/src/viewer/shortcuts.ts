import type { ViewerState } from './viewer-state.svelte';

export type ShortcutKey =
  | 'ArrowLeft'
  | 'ArrowRight'
  | 'PageUp'
  | 'PageDown'
  | 'Space'
  | 'KeyA'
  | 'KeyD'
  | 'KeyF'
  | 'Escape';

export interface ShortcutBinding {
  /** `KeyboardEvent.code` values that trigger this binding. */
  codes: ShortcutKey[];
  description: string;
  /** Consume the event so the browser does not also scroll / activate a focused button. */
  preventDefault: boolean;
  run: (viewer: ViewerState, event: KeyboardEvent) => void;
}

/**
 * Canonical binding table. Direction keys are idempotent under RTL: they always mean
 * "physically left" / "physically right", so the viewer state resolves them through
 * `goBackward` / `goForward`, which flip the semantic direction for RTL and webtoon.
 */
export const SHORTCUTS: ShortcutBinding[] = [
  {
    codes: ['ArrowLeft'],
    description: 'Previous page (mirrored for RTL)',
    preventDefault: true,
    run: (viewer) => viewer.goBackward(),
  },
  {
    codes: ['ArrowRight'],
    description: 'Next page (mirrored for RTL)',
    preventDefault: true,
    run: (viewer) => viewer.goForward(),
  },
  {
    codes: ['KeyA'],
    description: 'Previous page',
    preventDefault: false,
    run: (viewer) => viewer.goBackward(),
  },
  {
    codes: ['KeyD'],
    description: 'Next page',
    preventDefault: false,
    run: (viewer) => viewer.goForward(),
  },
  {
    codes: ['Space', 'PageDown'],
    description: 'Next page / scroll down',
    preventDefault: true,
    run: (viewer) => viewer.goForward(),
  },
  {
    codes: ['PageUp'],
    description: 'Previous page / scroll up',
    preventDefault: true,
    run: (viewer) => viewer.goBackward(),
  },
  {
    codes: ['KeyF'],
    description: 'Toggle fullscreen',
    preventDefault: false,
    run: (viewer) => viewer.toggleFullscreen(),
  },
  {
    codes: ['Escape'],
    description: 'Exit fullscreen, then stop auto-scroll',
    preventDefault: false,
    run: (viewer) => {
      if (viewer.isFullscreen) {
        viewer.toggleFullscreen();
      } else if (viewer.autoScroll) {
        viewer.autoScroll = false;
      }
    },
  },
];

/** Keys that must never be hijacked while the user is typing or a control has focus. */
const EDITABLE_SELECTOR =
  'input:not([type="range"]):not([type="checkbox"]):not([type="radio"]), textarea, select, [contenteditable=""], [contenteditable="true"]';

export function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  return target.matches(EDITABLE_SELECTOR);
}

/** Ignores OS/browser chords (⌘←, Ctrl+PageDown, Alt+F, …) which belong to the browser. */
function hasModifier(event: KeyboardEvent): boolean {
  return event.metaKey || event.ctrlKey || event.altKey;
}

export interface ShortcutOptions {
  /**
   * Element that owns the shortcuts. Defaults to `window` so the reader works regardless of
   * where focus sits.
   */
  target?: Window | HTMLElement;
  /** Skip our own handling instead of swallowing the key. Return `true` to ignore it. */
  shouldIgnore?: (event: KeyboardEvent) => boolean;
}

/**
 * Attaches the global keyboard shortcuts to a viewer state.
 *
 * @returns A disposer that removes the listener and can safely be called more than once.
 */
export function setupShortcuts(
  viewer: ViewerState,
  options: ShortcutOptions = {},
): () => void {
  const target: Window | HTMLElement | undefined =
    options.target ?? (typeof window === 'undefined' ? undefined : window);
  if (!target) return () => {};

  const lookup = new Map<string, ShortcutBinding>();
  for (const binding of SHORTCUTS) {
    for (const code of binding.codes) lookup.set(code, binding);
  }

  const handler = (rawEvent: Event): void => {
    if (!(rawEvent instanceof KeyboardEvent)) return;
    const event = rawEvent;

    // Keydown only: keyup would double-fire the action for held keys.
    if (event.key !== 'Escape' && event.isComposing) return;
    if (event.repeat && event.code !== 'ArrowLeft' && event.code !== 'ArrowRight') {
      // Holding A/D/Space should not machine-gun page flips.
      return;
    }
    if (hasModifier(event)) return;
    if (isEditableTarget(event.target)) return;
    if (options.shouldIgnore?.(event)) return;

    const binding = lookup.get(event.code);
    if (!binding) return;

    if (binding.preventDefault) event.preventDefault();
    binding.run(viewer, event);
  };

  target.addEventListener('keydown', handler);

  let disposed = false;
  return () => {
    if (disposed) return;
    disposed = true;
    target.removeEventListener('keydown', handler);
  };
}

/** Human readable cheat sheet, useful for an options / help surface. */
export function shortcutHelp(): { keys: string; description: string }[] {
  return [
    { keys: '← / A', description: 'Previous page' },
    { keys: '→ / D', description: 'Next page' },
    { keys: 'Space / PageDown', description: 'Next page' },
    { keys: 'PageUp', description: 'Previous page' },
    { keys: 'F', description: 'Toggle fullscreen' },
    { keys: 'Esc', description: 'Exit fullscreen / stop auto-scroll' },
  ];
}
