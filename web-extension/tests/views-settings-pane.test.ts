/**
 * Behaviour of `views/SettingsPane.svelte`, the 1:1 port of
 * `desktop/src/main/kotlin/ui/settings/` (`SettingsPane.kt` + the five `*SettingsPane.kt` panes and
 * `SettingsComponents.kt`), plus the store fields/actions behind it
 * (`DesktopStore.selectedSettingsCategory` / `selectSettingsCategory` / `closeSettingsCategory` /
 * `closeSettings` / `setAppLanguage` / `setViewMode` / `setReadDirection` / `setSplitMode`).
 *
 * The Kotlin screen is one `Scaffold`: a back arrow plus a category title, then either the five-card
 * hub (`selectedSettingsCategory == null`) or the sub-pane that category routes to. Both halves are
 * asserted here, along with the detail that is easy to get wrong from the Kotlin source:
 *
 * - the **hub's** back arrow is `viewModel.closeSettings()` (pop the category, else leave to Browse),
 *   while a **sub-pane's** back arrow is `selectSettingsCategory(null)` (always the hub);
 * - `openSettings` resets the category, so re-entering settings never lands in a stale sub-pane;
 * - the hub summaries follow the Kotlin rules (language label, `viewMode · direction`, sync state,
 *   network description, `labelAppVersion`);
 * - `TestBackoff`-free: no request leaves the component — `chrome.storage.local` is absent and the
 *   sync client is the store's uninitialised default, so `loadSyncState` only mints a local key.
 *
 * Styling is not asserted through `getComputedStyle`: happy-dom does not apply Svelte's scoped
 * styles, so class/data attributes (`.category-card[data-category]`, `.chip.selected`,
 * `.radio-option.selected`) are the stable contract.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';

import SettingsPane from '../src/views/SettingsPane.svelte';
import {
  appLanguageLabel,
  createDesktopStore,
  resolveAppLanguage,
  settingsTitleFor,
  splitModeDescription,
  splitModeLabel,
  viewModeDescription,
  viewModeLabel,
  viewModeShortLabel,
  readDirectionShortLabel,
  type AppLanguage,
  type DesktopStore,
  type SettingsCategory,
} from '../src/state/desktop-state.svelte';
import type { SourceRegistry } from '../src/sources/registry';

const tick = async () => {
  for (let i = 0; i < 20; i += 1) {
    await Promise.resolve();
    flushSync();
  }
};

/** Registry with no sources: `init()` never touches `chrome.storage`. */
function emptyRegistry(): SourceRegistry {
  return {
    init: async () => {},
    list: () => [],
    get: async () => null,
    uninstall: async () => false,
    install: async () => {
      throw new Error('not used');
    },
  } as unknown as SourceRegistry;
}

/** Store with an in-memory registry and no preference persistence (tests). */
function makeStore(): DesktopStore {
  return createDesktopStore({ registry: emptyRegistry(), persistPreferences: false });
}

let target: HTMLElement;

beforeEach(() => {
  if (typeof Animation !== 'undefined' && Animation.prototype) {
    Animation.prototype.cancel = () => {};
  }
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById('app')!;
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn(async () => undefined) },
  });
});

function mountPane(store: DesktopStore): Record<string, unknown> {
  const app = mount(SettingsPane, { target, props: { store } });
  flushSync();
  return app;
}

const title = (): string => target.querySelector('.title')?.textContent?.trim() ?? '';
const cards = (): HTMLElement[] => [
  ...target.querySelectorAll<HTMLElement>('.category-card'),
];
const cardFor = (category: SettingsCategory): HTMLElement =>
  cards().find((card) => card.getAttribute('data-category') === category)!;
const texts = (nodes: Element[]): (string | undefined)[] =>
  nodes.map((node) => node.textContent?.replace(/\s+/g, ' ').trim());

/** `data-*` marker of the rendered sub-pane (`content` carries the category). */
const renderedCategory = (): string =>
  target.querySelector('.content')?.getAttribute('data-category') ?? '';

function chips(selector: string): HTMLButtonElement[] {
  return [...target.querySelectorAll<HTMLButtonElement>(selector)];
}

// ---------------------------------------------------------------------------------------------
// store contract
// ---------------------------------------------------------------------------------------------

describe('DesktopStore settings navigation (DesktopViewModel.openSettings / closeSettings)', () => {
  it('starts on the hub and resets the category when settings opens', () => {
    const store = makeStore();
    expect(store.selectedSettingsCategory).toBeNull();

    store.selectSettingsCategory('READER');
    expect(store.selectedSettingsCategory).toBe('READER');

    // `_state.update { it.copy(screen = Screen.Settings, selectedSettingsCategory = null) }`
    store.openSettings();
    expect(store.screen).toBe('Settings');
    expect(store.selectedSettingsCategory).toBeNull();
  });

  it('pops the category first and only then leaves to Browse', () => {
    const store = makeStore();
    store.openSettings();
    store.selectSettingsCategory('SYNC_BACKUP');

    // Kotlin: if (selectedSettingsCategory != null) null else screen = Browse.
    store.closeSettings();
    expect(store.selectedSettingsCategory).toBeNull();
    expect(store.screen).toBe('Settings');

    store.closeSettings();
    expect(store.screen).toBe('Browse');
  });

  it('closeSettingsCategory always returns to the hub, from any category', () => {
    const store = makeStore();
    store.openSettings();
    for (const category of [
      'GENERAL',
      'READER',
      'SYNC_BACKUP',
      'NETWORK_DOWNLOAD',
      'ABOUT',
    ] as SettingsCategory[]) {
      store.selectSettingsCategory(category);
      store.closeSettingsCategory();
      expect(store.selectedSettingsCategory).toBeNull();
      expect(store.screen).toBe('Settings');
    }
  });

  it('setAppLanguage records the choice in the read model', () => {
    const store = makeStore();
    expect(store.appLanguage).toBe('AUTO');
    store.setAppLanguage('JA');
    expect(store.appLanguage).toBe('JA');
  });

  it('the reader setters update viewMode / readDirection / splitMode', () => {
    const store = makeStore();
    expect([store.viewMode, store.readDirection, store.splitMode]).toEqual([
      'DUAL',
      'RIGHT_TO_LEFT',
      'FIT',
    ]);

    store.setViewMode('SCROLL');
    store.setReadDirection('LEFT_TO_RIGHT');
    store.setSplitMode('SLICE');
    expect([store.viewMode, store.readDirection, store.splitMode]).toEqual([
      'SCROLL',
      'LEFT_TO_RIGHT',
      'SLICE',
    ]);
  });

  it('clearImageCache reports what it dropped', () => {
    const store = makeStore();
    // Nothing cached yet: the About pane then says "삭제할 캐시가 없습니다".
    expect(store.clearImageCache()).toBe(0);
  });
});

describe('settings label helpers (I18nExtensions.kt / AppLanguage.kt)', () => {
  it('titles every category and the hub', () => {
    expect(settingsTitleFor(null)).toBe('설정');
    expect(settingsTitleFor('GENERAL')).toBe('일반');
    expect(settingsTitleFor('READER')).toBe('뷰어 기본 설정');
    expect(settingsTitleFor('SYNC_BACKUP')).toBe('동기화 및 백업');
    expect(settingsTitleFor('NETWORK_DOWNLOAD')).toBe('네트워크 설정');
    expect(settingsTitleFor('ABOUT')).toBe('앱 정보 및 기타');
  });

  it('labels every language with its native name', () => {
    expect(appLanguageLabel('AUTO')).toBe('자동 (Auto)');
    expect(appLanguageLabel('KO')).toBe('한국어');
    expect(appLanguageLabel('EN')).toBe('English');
    expect(appLanguageLabel('JA')).toBe('日本語');
    expect(appLanguageLabel('ZH_CN')).toBe('简体中文');
    expect(appLanguageLabel('ZH_TW')).toBe('繁體中文');
  });

  it('resolves AUTO against the system locale like AppLanguage.resolve', () => {
    expect(resolveAppLanguage('KO')).toBe('KO');
    expect(resolveAppLanguage('AUTO', 'ko-KR')).toBe('KO');
    expect(resolveAppLanguage('AUTO', 'ja-JP')).toBe('JA');
    expect(resolveAppLanguage('AUTO', 'zh-Hant-TW')).toBe('ZH_TW');
    expect(resolveAppLanguage('AUTO', 'zh-CN')).toBe('ZH_CN');
    expect(resolveAppLanguage('AUTO', 'en-US')).toBe('EN');
    // Unknown locales fall back to KO, exactly like the Kotlin `else -> KO`.
    expect(resolveAppLanguage('AUTO', 'fr-FR')).toBe('KO');
  });

  it('copies the ViewMode / ReadDirection / SplitMode display labels', () => {
    expect(viewModeLabel('SCROLL')).toBe('세로 스크롤');
    expect(viewModeLabel('PAGE')).toBe('단면 보기');
    expect(viewModeLabel('DUAL')).toBe('양면 분할');
    expect(viewModeShortLabel('SCROLL')).toBe('스크롤');
    expect(viewModeShortLabel('PAGE')).toBe('단면');
    expect(viewModeShortLabel('DUAL')).toBe('양면');
    expect(viewModeDescription('SCROLL')).toBe('세로로 연속 스크롤하며 읽기');
    expect(readDirectionShortLabel('RIGHT_TO_LEFT')).toBe('우→좌');
    expect(readDirectionShortLabel('LEFT_TO_RIGHT')).toBe('좌→우');
    expect(splitModeLabel('FIT')).toBe('화면 맞춤 (Fit)');
    expect(splitModeLabel('SLICE')).toBe('2장 분할 (Slice)');
    expect(splitModeDescription('SLICE')).toContain('좌/우 페이지로 분할');
  });
});

// ---------------------------------------------------------------------------------------------
// hub
// ---------------------------------------------------------------------------------------------

describe('SettingsPane — category hub', () => {
  it('titles itself 설정 and renders the five Kotlin cards in order', async () => {
    const store = makeStore();
    const app = mountPane(store);
    await tick();

    expect(title()).toBe('설정');
    expect(cards().map((card) => card.getAttribute('data-category'))).toEqual([
      'GENERAL',
      'READER',
      'SYNC_BACKUP',
      'NETWORK_DOWNLOAD',
      'ABOUT',
    ]);
    // `SettingsCategoryCard`: title (titleMedium bold) above the 2-line summary.
    expect(
      texts([...target.querySelectorAll<HTMLElement>('.category-card .category-title')]),
    ).toEqual([
      '일반 설정',
      '뷰어 기본 설정',
      '동기화 및 백업',
      '네트워크 설정',
      '앱 정보 및 기타',
    ]);
    unmount(app);
  });

  it('summarises the current language, viewer defaults, sync state, network and version', async () => {
    const store = makeStore();
    store.appLanguage = 'KO';
    store.viewMode = 'SCROLL';
    store.readDirection = 'LEFT_TO_RIGHT';
    const app = mountPane(store);
    await tick();

    expect(cardFor('GENERAL').textContent).toContain('앱 표시 언어: 한국어');
    expect(cardFor('READER').textContent).toContain('스크롤 · 좌→우');
    // `lastSyncedAt === 0` → `strings.descSyncAndBackup`.
    expect(cardFor('SYNC_BACKUP').textContent).toContain('클라우드 동기화, 기기 연동');
    expect(cardFor('NETWORK_DOWNLOAD').textContent).toContain('WebDAV 원격 스토리지');
    expect(cardFor('ABOUT').textContent).toContain('버전 v0.2.48');
    unmount(app);
  });

  it('switches the sync summary to 클라우드 연결됨 once a sync timestamp exists', async () => {
    const store = makeStore();
    const app = mountPane(store);
    await tick();

    // `onMount` hydrates `syncState` from the client, so the timestamp is set afterwards.
    store.syncState = { ...store.syncState, lastSyncedAt: Date.now() - 5 * 60_000 };
    flushSync();

    expect(cardFor('SYNC_BACKUP').textContent).toContain('클라우드 연결됨');
    unmount(app);
  });

  it('routes every card to its category and keeps the hub reachable', async () => {
    const store = makeStore();
    const app = mountPane(store);
    await tick();

    const expected: Record<SettingsCategory, string> = {
      GENERAL: '일반',
      READER: '뷰어 기본 설정',
      SYNC_BACKUP: '동기화 및 백업',
      NETWORK_DOWNLOAD: '네트워크 설정',
      ABOUT: '앱 정보 및 기타',
    };

    for (const [category, heading] of Object.entries(expected) as [
      SettingsCategory,
      string,
    ][]) {
      cardFor(category).click();
      await tick();
      expect(store.selectedSettingsCategory).toBe(category);
      expect(renderedCategory()).toBe(category);
      expect(title()).toBe(heading);

      // The sub-pane's own back arrow is `selectSettingsCategory(null)`.
      target.querySelector<HTMLElement>('.top-bar .icon-button')!.click();
      await tick();
      expect(store.selectedSettingsCategory).toBeNull();
      expect(target.querySelectorAll('.category-card').length).toBe(5);
    }
    unmount(app);
  });

  it('the hub back arrow closes settings entirely (closeSettings)', async () => {
    const store = makeStore();
    store.openSettings();
    const closeSettings = vi.spyOn(store, 'closeSettings');
    const app = mountPane(store);
    await tick();

    target.querySelector<HTMLElement>('.top-bar .icon-button')!.click();
    expect(closeSettings).toHaveBeenCalledTimes(1);
    unmount(app);
  });
});

// ---------------------------------------------------------------------------------------------
// GENERAL
// ---------------------------------------------------------------------------------------------

describe('SettingsPane — 일반 설정 (GeneralSettingsPane.kt)', () => {
  it('offers all six AppLanguage entries and persists the choice', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'GENERAL';
    const app = mountPane(store);
    await tick();

    const select = target.querySelector<HTMLSelectElement>('select.select')!;
    expect([...select.options].map((option) => option.value)).toEqual([
      'AUTO',
      'KO',
      'EN',
      'JA',
      'ZH_CN',
      'ZH_TW',
    ]);
    expect([...select.options].map((option) => option.textContent)).toEqual([
      '자동 (Auto)',
      '한국어',
      'English',
      '日本語',
      '简体中文',
      '繁體中文',
    ]);

    select.value = 'ZH_TW';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    flushSync();
    expect(store.appLanguage).toBe<AppLanguage>('ZH_TW');
    unmount(app);
  });
});

// ---------------------------------------------------------------------------------------------
// READER
// ---------------------------------------------------------------------------------------------

describe('SettingsPane — 뷰어 기본 설정 (ReaderSettingsPane.kt)', () => {
  it('renders the three chip groups and applies the Kotlin setters', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'READER';
    const app = mountPane(store);
    await tick();

    const modeChips = chips('.chip.view-mode');
    expect(modeChips.map((chip) => chip.getAttribute('data-view-mode'))).toEqual([
      'SCROLL',
      'PAGE',
      'DUAL',
    ]);
    expect(texts(modeChips)).toEqual(['세로 스크롤', '단면 보기', '양면 분할']);
    // DUAL is the default, so only that chip is pressed.
    expect(modeChips.map((chip) => chip.classList.contains('selected'))).toEqual([
      false,
      false,
      true,
    ]);

    modeChips[0]!.click();
    flushSync();
    expect(store.viewMode).toBe('SCROLL');
    expect(chips('.chip.view-mode')[0]!.classList.contains('selected')).toBe(true);

    const directionChips = chips('.chip.read-direction');
    expect(directionChips.map((chip) => chip.getAttribute('data-read-direction'))).toEqual([
      'RIGHT_TO_LEFT',
      'LEFT_TO_RIGHT',
    ]);
    expect(texts(directionChips)).toEqual(['오른쪽 → 왼쪽', '왼쪽 → 오른쪽']);
    directionChips[1]!.click();
    flushSync();
    expect(store.readDirection).toBe('LEFT_TO_RIGHT');

    const splitChips = chips('.chip.split-mode');
    expect(splitChips.map((chip) => chip.getAttribute('data-split-mode'))).toEqual(['FIT', 'SLICE']);
    expect(texts(splitChips)).toEqual(['화면 맞춤 (Fit)', '2장 분할 (Slice)']);
    splitChips[1]!.click();
    flushSync();
    expect(store.splitMode).toBe('SLICE');
    unmount(app);
  });
});

// ---------------------------------------------------------------------------------------------
// SYNC_BACKUP
// ---------------------------------------------------------------------------------------------

describe('SettingsPane — 동기화 및 백업 (SyncBackupSettingsPane.kt)', () => {
  it('shows the sync state, the PIN input, the sync key and 지금 동기화', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'SYNC_BACKUP';
    const app = mountPane(store);
    await tick();

    // Base state after `store.loadSyncState()` (the client mints a local key on `init`).
    store.syncState = {
      ...store.syncState,
      syncKey: 'C8-ABCD-EFGH-IJKL',
      serverUrl: 'https://sync.test/api',
    };
    flushSync();

    // `labelNoSyncHistory` before the first sync; matching SyncBackupSettingsPane.kt 1:1.
    expect(target.textContent).toContain('동기화 이력 없음');
    expect(target.textContent).toContain('새 기기 연결 (페어링 코드 생성)');
    expect(target.textContent).toContain('다른 기기 연결');
    expect(target.textContent).toContain('백업 파일 저장 (Export)');
    expect(target.textContent).toContain('백업 파일 복원 (Import)');

    // `actionSyncNow` is labelled `동기화`.
    const syncButton = [...target.querySelectorAll<HTMLButtonElement>('.btn.primary')].find((button) =>
      button.textContent?.includes('동기화'),
    );
    expect(syncButton).toBeDefined();
    unmount(app);
  });

  it('toggles the auto-sync switch through DesktopViewModel.toggleAutoSync', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'SYNC_BACKUP';
    const toggle = vi.spyOn(store, 'toggleAutoSync').mockResolvedValue();
    const app = mountPane(store);
    await tick();

    const checkbox = target.querySelector<HTMLInputElement>('.switch input')!;
    expect(checkbox.checked).toBe(true);
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change', { bubbles: true }));
    await tick();
    expect(toggle).toHaveBeenCalledWith(false);
    unmount(app);
  });

  it('syncNow reports how many rows it pushed', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'SYNC_BACKUP';
    const syncNow = vi
      .spyOn(store, 'syncNow')
      .mockResolvedValue({ ok: true, message: '동기화 완료 — 즐겨찾기 2 · 기록 1 · 읽은 회차 3', favorites: 2, history: 1, readEpisodes: 3 });
    const app = mountPane(store);
    await tick();

    const button = [...target.querySelectorAll<HTMLButtonElement>('.btn.primary')].find((node) =>
      node.textContent?.includes('동기화'),
    )!;
    button.click();
    await tick();

    expect(syncNow).toHaveBeenCalledTimes(1);
    expect(target.querySelector('.inline-notice.ok')?.textContent).toContain('즐겨찾기 2');
    unmount(app);
  });

  it('opens the advanced key section and re-issues a master key', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'SYNC_BACKUP';
    store.syncState = {
      ...store.syncState,
      syncKey: 'C8-ABCD-EFGH-IJKL',
    };
    const generate = vi.spyOn(store, 'generateNewSyncKey').mockResolvedValue();
    const app = mountPane(store);
    await tick();

    const expander = target.querySelector<HTMLButtonElement>('.expander')!;
    expect(expander.getAttribute('aria-expanded')).toBe('false');
    expander.click();
    flushSync();
    expect(expander.getAttribute('aria-expanded')).toBe('true');
    expect(target.textContent).toContain(store.syncState.syncKey);

    const reissue = target.querySelector<HTMLButtonElement>('.btn-text')!;
    reissue.click();
    await tick();
    expect(generate).toHaveBeenCalledTimes(1);
    unmount(app);
  });
});

// ---------------------------------------------------------------------------------------------
// NETWORK_DOWNLOAD
// ---------------------------------------------------------------------------------------------

describe('SettingsPane — 네트워크 설정 (NetworkSettingsPane.kt)', () => {
  it('keeps the WebDAV form and the proxy rows', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'NETWORK_DOWNLOAD';
    const app = mountPane(store);
    await tick();

    const urlInput = target.querySelector<HTMLInputElement>('input[type=url]')!;
    expect(urlInput.getAttribute('placeholder')).toBe('https://dav.example.com/comics');
    // The connection test is disabled while the URL is blank.
    const testButton = [...target.querySelectorAll<HTMLButtonElement>('.btn')].find((button) =>
      button.textContent?.includes('연결 테스트'),
    )!;
    expect(testButton.disabled).toBe(true);

    urlInput.value = 'https://dav.test/comics';
    urlInput.dispatchEvent(new Event('input', { bubbles: true }));
    flushSync();
    expect(testButton.disabled).toBe(false);

    // `setProxyType(ProxyType.DIRECT | SERVER)`.
    const proxyRows = [...target.querySelectorAll<HTMLElement>('.radio-option')];
    expect(proxyRows.map((row) => row.getAttribute('data-proxy-type'))).toEqual([
      'DIRECT',
      'SERVER',
    ]);
    proxyRows[1]!.click();
    flushSync();
    expect(store.networkSettings.proxyType).toBe('SERVER');
    unmount(app);
  });

  it('runs the connection test through DesktopViewModel.testProxyConnection', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'NETWORK_DOWNLOAD';
    const test = vi
      .spyOn(store, 'testProxyConnection')
      .mockResolvedValue({ ok: true, message: '직접 연결: 기기의 네트워크로 사이트에 접속합니다.' });
    const app = mountPane(store);
    await tick();

    const button = [...target.querySelectorAll<HTMLButtonElement>('.btn')].find((node) =>
      node.textContent?.trim() === '테스트',
    )!;
    button.click();
    await tick();
    expect(test).toHaveBeenCalledTimes(1);
    unmount(app);
  });
});

// ---------------------------------------------------------------------------------------------
// ABOUT
// ---------------------------------------------------------------------------------------------

describe('SettingsPane — 앱 정보 및 기타 (AboutSettingsPane.kt)', () => {
  it('shows the app version, the MIT licence and clears the cache', async () => {
    const store = makeStore();
    store.selectedSettingsCategory = 'ABOUT';
    const clear = vi.spyOn(store, 'clearImageCache').mockReturnValue(3);
    const app = mountPane(store);
    await tick();

    expect(target.textContent).toContain('Comics8');
    expect(target.textContent).toContain('버전 v0.2.48');
    expect(target.textContent).toContain('업데이트 확인');
    expect(target.textContent).toContain('MIT License');

    const clearButton = [...target.querySelectorAll<HTMLButtonElement>('.btn.danger')][0]!;
    expect(clearButton.textContent?.trim()).toBe('캐시 삭제');
    clearButton.click();
    flushSync();

    expect(clear).toHaveBeenCalledTimes(1);
    expect(target.textContent).toContain('캐시를 삭제했습니다. (항목 3개)');
    unmount(app);
  });
});
