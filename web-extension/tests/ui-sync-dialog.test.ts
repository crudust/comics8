/**
 * Behaviour of `src/ui/SyncDialog.svelte`, the 1:1 port of
 * `desktop/src/main/kotlin/ui/components/SyncDialog.kt` (lines 64-617).
 *
 * The Kotlin side is `SyncDialog(state, viewModel, onDismiss)` composed of one `AlertDialog` plus two
 * nested dialogs; the web twin takes the collapsed `store` (`DesktopUiState` + `DesktopViewModel`)
 * plus `onDismiss`. These tests drive the real `DesktopStore` against a fake `SyncClient`, so every
 * assertion is about the same call the Kotlin dialog makes:
 *
 * - main dialog: `titleCloudSync` + `descCloudSync`, the two pairing entry buttons, the divider, the
 *   `labelAutoSync`/`descAutoSync` switch (`toggleAutoSync`), the fold row
 *   (`labelUnfoldAdvancedSync`/`labelFoldAdvancedSync`) over the master-key card
 *   (`labelMyMasterKey` + copy + `actionReissueMasterKey`), and
 *   `labelLastSyncedAt("yyyy-MM-dd HH:mm:ss")` / `labelNoSyncHistory`; confirm = `actionSyncNow`,
 *   dismiss = `actionClose`;
 * - `DesktopSyncDialogPairingCode`: `requestPairingCode` on open, `123 456`, the 300s countdown and
 *   `actionIssueNewCode` / `actionClose`;
 * - `DesktopSyncDialogConnectDevice`: code vs master-key mode, `confirmPairingCode` /
 *   `updateSyncKey` (`restoreWithMasterKey`), one banner, `actionImportData` / `actionCancel`.
 *
 * Nothing reaches the network: `SyncClient` is replaced and the clipboard is a stub.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';

import ReaderPane from '../src/viewer/ReaderPane.svelte';
import SyncDialog from '../src/ui/SyncDialog.svelte';
import TopBar from '../src/ui/TopBar.svelte';
import { DesktopStore } from '../src/state/desktop-state.svelte';
import type {
  PairConfirmResult,
  PairRequestResult,
  PullResult,
  PushResult,
  SyncClient,
  SyncStatus,
  SyncUpdates,
} from '../src/sync/sync-client';

const tick = async () => {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve();
    flushSync();
  }
};

const status = (overrides: Partial<SyncStatus> = {}): SyncStatus => ({
  syncKey: 'C8-TEST-TEST-TEST',
  serverUrl: 'https://sync.test/api',
  lastSyncedAt: 0,
  lastResult: 'idle',
  lastError: null,
  isPro: false,
  autoSyncEnabled: true,
  ...overrides,
});

type FakeClient = {
  client: SyncClient;
  calls: {
    init: number;
    requestPairingCode: number;
    confirmPairingCode: string[];
    pull: number;
    push: SyncUpdates[];
    generateNewKey: number;
    setSyncKey: string[];
    setAutoSyncEnabled: boolean[];
  };
};

/**
 * Minimal `SyncClient` stand-in for the calls `DesktopStore`'s sync section makes.
 *
 * `push` also records `lastSyncedAt`/`lastResult` so `refreshSyncState` (i.e. the rendered
 * `labelLastSyncedAt`) sees what a real POST /sync would return.
 */
function fakeClient(
  options: {
    initial?: Partial<SyncStatus>;
    pairingCode?: PairRequestResult;
    requestPairingCode?: () => Promise<PairRequestResult>;
    confirm?: (code: string) => Promise<PairConfirmResult>;
    push?: (updates: SyncUpdates) => Promise<Partial<PushResult>>;
    pull?: Partial<PullResult>;
    generateKey?: string;
    installKey?: string;
  } = {},
): FakeClient {
  const state = status(options.initial);
  const calls: FakeClient['calls'] = {
    init: 0,
    requestPairingCode: 0,
    confirmPairingCode: [],
    pull: 0,
    push: [],
    generateNewKey: 0,
    setSyncKey: [],
    setAutoSyncEnabled: [],
  };

  const client = {
    async init(): Promise<SyncStatus> {
      calls.init += 1;
      return { ...state };
    },
    getStatus(): SyncStatus {
      return { ...state };
    },
    async requestPairingCode(): Promise<PairRequestResult> {
      calls.requestPairingCode += 1;
      if (options.requestPairingCode) return options.requestPairingCode();
      return options.pairingCode ?? { success: true, code: '123456', expiresInSeconds: 300 };
    },
    async confirmPairingCode(code: string): Promise<PairConfirmResult> {
      calls.confirmPairingCode.push(code);
      if (options.confirm) {
        const result = await options.confirm(code);
        state.syncKey = result.syncKey;
        return result;
      }
      state.syncKey = 'C8-PAIRED-KEY1-XXXX';
      return { syncKey: state.syncKey };
    },
    async pull(): Promise<PullResult> {
      calls.pull += 1;
      return {
        favorites: [],
        history: [],
        readEpisodes: [],
        readerSettings: [],
        tombstones: [],
        catalog: [],
        version: 2,
        exportedAt: Date.now(),
        isPro: false,
        ...options.pull,
      };
    },
    async push(updates: SyncUpdates): Promise<PushResult> {
      calls.push.push(updates);
      const overrides = options.push ? await options.push(updates) : {};
      state.lastSyncedAt = Date.now();
      state.lastResult = 'success';
      state.lastError = null;
      return {
        status: 'success',
        savedAt: state.lastSyncedAt,
        userId: 'u1',
        sizeBytes: 1,
        favorites: updates.favorites.length,
        history: updates.history.length,
        readEpisodes: updates.readEpisodes.length,
        isPro: false,
        ...overrides,
      };
    },
    async generateNewKey(): Promise<SyncStatus> {
      calls.generateNewKey += 1;
      state.syncKey = options.generateKey ?? 'C8-NEWK-NEWK-NEWK';
      state.lastSyncedAt = 0;
      state.lastResult = 'idle';
      return { ...state };
    },
    async setSyncKey(key: string): Promise<SyncStatus> {
      calls.setSyncKey.push(key);
      state.syncKey = key;
      state.lastSyncedAt = 0;
      return { ...state };
    },
    async setAutoSyncEnabled(enabled: boolean): Promise<SyncStatus> {
      calls.setAutoSyncEnabled.push(enabled);
      state.autoSyncEnabled = enabled;
      return { ...state };
    },
    // The browse grid asks the public catalog for its missing totals; this dialog never triggers
    // that, but the store must still see a client that answers instead of throwing.
    async fetchCatalogBatch(): Promise<never[]> {
      return [];
    },
  } as unknown as SyncClient;

  return { client, calls };
}

/** `state` + `viewModel` collapsed into the store the component takes, like the shell does. */
function makeStore(options: Parameters<typeof fakeClient>[0] = {}): {
  store: DesktopStore;
  calls: FakeClient['calls'];
} {
  const { client, calls } = fakeClient(options);
  const store = new DesktopStore({ persistPreferences: false, syncClient: client });
  return { store, calls };
}

function query<T extends Element = HTMLElement>(selector: string): T {
  const node = target.querySelector<T>(selector);
  if (!node) throw new Error(`missing ${selector}`);
  return node;
}

function buttonByText(text: string): HTMLButtonElement {
  const match = [...target.querySelectorAll<HTMLButtonElement>('button')].find(
    (node) => node.textContent?.trim().startsWith(text),
  );
  if (!match) throw new Error(`missing button ${text}`);
  return match;
}

function typeInto(selector: string, value: string): void {
  const input = query<HTMLInputElement>(selector);
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  flushSync();
}

let target: HTMLElement;
/** Every component mounted by a test; `afterEach` unmounts them even when an assertion throws. */
let mounted: Record<string, unknown>[] = [];

function render<T extends Record<string, unknown>>(component: unknown, props: T): void {
  mounted.push(mount(component as never, { target, props }) as Record<string, unknown>);
}

beforeEach(() => {
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById('app')!;
  mounted = [];
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn(async () => undefined) },
  });
});

afterEach(() => {
  vi.useRealTimers();
  // A left-mounted dialog keeps its `window` capture listener, which would swallow the next
  // test's Escape events; unmount every component even when an assertion threw.
  for (const app of mounted.splice(0).reverse()) unmount(app as never);
});

describe('SyncDialog — main dialog (SyncDialog.kt)', () => {
  it('renders the Kotlin title, description, both pairing actions and the footer', async () => {
    const { store, calls } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    // `init()` seeds the sync state without any server round trip.
    expect(calls.init).toBe(1);

    expect(query('.dialog-title').textContent).toBe('클라우드 자동 동기화');
    expect(query('.title-icon')).not.toBeNull();
    expect(query('.desc').textContent).toBe(
      '개인정보 없이 6자리 페어링 코드로 스마트폰(Android), 태블릿 등 다른 기기와 즐겨찾기 및 읽음 진행도를 실시간 동기화합니다.',
    );

    // `FilledTonalButton(actionOpenPairingDialog)` / `OutlinedButton(actionOpenConnectDialogShort)`.
    const pairing = query<HTMLButtonElement>('.action.filled-tonal.open-pairing');
    expect(pairing.textContent).toContain('새 기기 연결하기 (6자리 코드 발급)');
    const connect = query<HTMLButtonElement>('.action.outlined.open-connect');
    expect(connect.textContent).toContain('다른 기기의 데이터 가져오기');

    // `HorizontalDivider`.
    expect(query('.divider')).not.toBeNull();

    // `labelAutoSync` / `descAutoSync` + `Switch(syncState.autoSyncEnabled)`.
    expect(query('.auto-sync-row .row-title').textContent).toBe('백그라운드 자동 동기화');
    expect(query('.auto-sync-row .row-desc').textContent).toBe(
      '만화 열람/즐겨찾기 변경 시 서버에 실시간 자동 동기화',
    );
    const checkbox = query<HTMLInputElement>('.switch input');
    expect(checkbox.checked).toBe(true);

    // Folded advanced section: only the row is visible, no key card.
    const fold = query<HTMLButtonElement>('.fold-row');
    expect(fold.textContent).toContain('고급 동기화 설정 (마스터 복구 키)');
    expect(fold.getAttribute('aria-expanded')).toBe('false');
    expect(target.querySelector('.advanced-card')).toBeNull();

    // `labelNoSyncHistory` with no acknowledged sync.
    expect(query('.last-synced').textContent).toBe('동기화 이력 없음');

    // Footer: `actionClose` (dismiss) + `actionSyncNow` (confirm).
    expect(buttonByText('닫기')).not.toBeNull();
    const syncNow = query<HTMLButtonElement>('.action.primary.sync-now');
    expect(syncNow.textContent).toContain('지금 즉시 동기화');
    expect(syncNow.disabled).toBe(false);
  });

  it('dismisses through the backdrop, the 닫기 button and Escape', async () => {
    const { store } = makeStore();
    const onDismiss = vi.fn();
    render(SyncDialog, { store, onDismiss });
    await tick();

    query<HTMLElement>('.dialog-backdrop').click();
    expect(onDismiss).toHaveBeenCalledTimes(1);

    // Clicking inside the dialog must not reach the backdrop handler.
    query<HTMLElement>('.dialog-body').click();
    expect(onDismiss).toHaveBeenCalledTimes(1);

    query<HTMLElement>('[data-testid=sync-dialog]').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    await tick();
    expect(onDismiss).toHaveBeenCalledTimes(2);

    // The window capture handler covers a dialog that does not hold focus.
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await tick();
    expect(onDismiss).toHaveBeenCalledTimes(3);

    buttonByText('닫기').click();
    expect(onDismiss).toHaveBeenCalledTimes(4);
  });

  it('toggleAutoSync follows the switch', async () => {
    const { store, calls } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    const checkbox = query<HTMLInputElement>('.switch input');
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change', { bubbles: true }));
    await tick();

    expect(calls.setAutoSyncEnabled).toEqual([false]);
    expect(query<HTMLInputElement>('.switch input').checked).toBe(false);

    const again = query<HTMLInputElement>('.switch input');
    again.checked = true;
    again.dispatchEvent(new Event('change', { bubbles: true }));
    await tick();
    expect(calls.setAutoSyncEnabled).toEqual([false, true]);
  });

  it('folds open the master-key card: copy + reissue', async () => {
    const { store, calls } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.fold-row').click();
    flushSync();
    // `labelFoldAdvancedSync` replaces the unfold wording.
    expect(query('.fold-row').textContent).toContain('고급 동기화 설정 접기');
    expect(query('.fold-row').getAttribute('aria-expanded')).toBe('true');

    expect(query('.advanced-label').textContent).toBe('내 마스터 복구 키 (비상용)');
    // `syncState.syncKey` in Monospace / primary.
    expect(query('.advanced-key').textContent).toBe('C8-TEST-TEST-TEST');

    query<HTMLButtonElement>('.copy-key').click();
    await tick();
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('C8-TEST-TEST-TEST');

    // `actionReissueMasterKey` → `viewModel.generateNewSyncKey()`.
    query<HTMLButtonElement>('.reissue').click();
    await tick();
    expect(calls.generateNewKey).toBe(1);
    expect(query('.advanced-key').textContent).toBe('C8-NEWK-NEWK-NEWK');
    // `generateNewKey` clears `lastSyncedAt`, so no history remains.
    expect(query('.last-synced').textContent).toBe('동기화 이력 없음');
  });

  it('labels the last sync with the Kotlin yyyy-MM-dd HH:mm:ss format', async () => {
    const lastSyncedAt = new Date(2026, 9, 26, 14, 5, 7).getTime();
    const { store } = makeStore({ initial: { lastSyncedAt, lastResult: 'success' } });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    expect(query('.last-synced').textContent).toBe('마지막 동기화: 2026-10-26 14:05:07');
  });

  it('syncs the local snapshot on 지금 즉시 동기화', async () => {
    const { store, calls } = makeStore({ initial: { lastSyncedAt: 1, lastResult: 'success' } });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.sync-now').click();
    await tick();

    // `DesktopStore.syncNow` is the pull-then-push pair (`mergeRemoteHistory` needs the server row).
    expect(calls.pull).toBe(1);
    expect(calls.push).toHaveLength(1);
    expect(calls.push[0]).toMatchObject({
      favorites: [],
      history: [],
      readEpisodes: [],
      readerSettings: [],
    });
    expect(target.querySelector('.banner')).toBeNull();
    expect(query('.last-synced').textContent).toMatch(/^마지막 동기화: \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });

  it('shows statusSyncing and disables the confirm button while a sync runs', async () => {
    let release: (() => void) | null = null;
    const { store } = makeStore({
      push: () =>
        new Promise<Partial<PushResult>>((resolve) => {
          release = () => resolve({});
        }),
    });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.sync-now').click();
    await tick();

    const busy = query<HTMLButtonElement>('.sync-now');
    expect(busy.disabled).toBe(true);
    expect(busy.textContent).toContain('동기화 중...');
    expect(busy.querySelector('.spin')).not.toBeNull();

    release!();
    await tick();
    expect(query<HTMLButtonElement>('.sync-now').disabled).toBe(false);
    expect(query('.sync-now').textContent).toContain('지금 즉시 동기화');
  });

  it('surfaces a failed sync in the error banner', async () => {
    const { store } = makeStore({
      push: async () => {
        throw new Error('HTTP 503');
      },
    });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.sync-now').click();
    await tick();

    const banner = query('.banner.error');
    expect(banner.getAttribute('role')).toBe('alert');
    expect(banner.textContent).toContain('HTTP 503');
  });
});

describe('SyncDialog — DesktopSyncDialogPairingCode', () => {
  it('issues the code on open and shows 123 456 with the 300s countdown', async () => {
    const { store, calls } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    expect(target.querySelector('[data-testid=sync-pairing-dialog]')).toBeNull();
    query<HTMLButtonElement>('.open-pairing').click();
    await tick();

    // `LaunchedEffect(Unit) { loadCode() }` — the code dialog requests immediately.
    expect(calls.requestPairingCode).toBe(1);
    expect(query('[data-testid=sync-pairing-dialog] .dialog-title').textContent).toBe(
      '새 기기 연결 (페어링)',
    );
    expect(query('.pairing-dialog .desc').textContent).toBe(
      '새로 연결할 기기(스마트폰, 태블릿, PC 등)의 [데이터 가져오기] 화면에서 아래 6자리 코드를 입력하세요.',
    );

    expect(query('.code').textContent?.trim()).toBe('123 456');
    expect(query('.code-timer').textContent?.trim()).toBe('⏱️ 남은 시간: 05:00');

    // `actionIssueNewCode` re-requests; `actionClose` only closes the nested dialog.
    buttonByText('새 코드 발급').click();
    await tick();
    expect(calls.requestPairingCode).toBe(2);

    // Both dialogs carry `actionClose`; this one belongs to the nested pairing dialog.
    const pairingClose = [...query('[data-testid=sync-pairing-dialog]').querySelectorAll<HTMLButtonElement>('button')]
      .find((node) => node.textContent?.trim() === '닫기')!;
    pairingClose.click();
    flushSync();
    expect(target.querySelector('[data-testid=sync-pairing-dialog]')).toBeNull();
    expect(target.querySelector('[data-testid=sync-dialog]')).not.toBeNull();
  });

  it('copies the raw six digits', async () => {
    const { store } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-pairing').click();
    await tick();

    query<HTMLButtonElement>('.copy-code').click();
    await tick();

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('123456');
    // `actionCopy` — Kotlin does not swap the label, only writes the clipboard.
    expect(query('.copy-code').textContent).toContain('복사');
  });

  it('prints errorPairingCodeExpired once the countdown reaches zero', async () => {
    vi.useFakeTimers();
    const { store } = makeStore({
      pairingCode: { success: true, code: '654321', expiresInSeconds: 2 },
    });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-pairing').click();
    await tick();

    expect(query('.code').textContent?.trim()).toBe('654 321');
    expect(query('.code-timer').textContent).toContain('남은 시간: 00:02');

    await vi.advanceTimersByTimeAsync(3_000);
    flushSync();

    expect(query('.code-timer').textContent).toContain('코드가 만료되었습니다.');
    expect(query('.code-timer').classList.contains('expired')).toBe(true);
  });

  it('reports a failed request instead of a code card', async () => {
    const { store } = makeStore({
      requestPairingCode: async () => {
        throw new Error('계정에 저장된 데이터가 없습니다');
      },
    });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-pairing').click();
    await tick();

    expect(target.querySelector('.code-card')).toBeNull();
    expect(query('.pairing-dialog .banner.error').textContent).toContain(
      '계정에 저장된 데이터가 없습니다',
    );
  });

  it('Escape closes the nested dialog before the main one', async () => {
    const { store } = makeStore();
    const onDismiss = vi.fn();
    render(SyncDialog, { store, onDismiss });
    await tick();

    query<HTMLButtonElement>('.open-pairing').click();
    await tick();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await tick();

    expect(target.querySelector('[data-testid=sync-pairing-dialog]')).toBeNull();
    expect(target.querySelector('[data-testid=sync-dialog]')).not.toBeNull();
    expect(onDismiss).not.toHaveBeenCalled();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await tick();
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});

describe('SyncDialog — DesktopSyncDialogConnectDevice', () => {
  it('starts in 6-digit mode with placeholderSixDigitCode and filters the input', async () => {
    const { store, calls } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-connect').click();
    flushSync();

    expect(query('.connect-dialog .dialog-title').textContent).toBe('기존 데이터 가져오기');
    expect(query('.connect-dialog .desc').textContent).toBe(
      '기존에 사용하던 기기의 [새 기기 연결하기]에 표시된 6자리 번호를 입력하세요.',
    );

    const field = query<HTMLInputElement>('.code-field');
    expect(field.getAttribute('placeholder')).toBe('예: 849201');
    expect(field.getAttribute('maxlength')).toBe('6');

    // `onValueChange = { it.filter { isDigit }.take(6) }`.
    typeInto('.code-field', '12ab34');
    expect(query<HTMLInputElement>('.code-field').value).toBe('1234');
    typeInto('.code-field', '1234567');
    expect(query<HTMLInputElement>('.code-field').value).toBe('123456');

    // `confirmButton` is disabled until six digits are present.
    const importButton = query<HTMLButtonElement>('.action.primary.import-data');
    expect(importButton.disabled).toBe(false);
    expect(importButton.textContent).toContain('데이터 가져오기');
    expect(calls.confirmPairingCode).toEqual([]);
  });

  it('keeps 데이터 가져오기 disabled until the input is complete', async () => {
    const { store } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-connect').click();
    flushSync();

    expect(query<HTMLButtonElement>('.import-data').disabled).toBe(true);
    typeInto('.code-field', '12345');
    expect(query<HTMLButtonElement>('.import-data').disabled).toBe(true);
    typeInto('.code-field', '123456');
    expect(query<HTMLButtonElement>('.import-data').disabled).toBe(false);
  });

  it('confirms the code and dismisses the nested dialog', async () => {
    const { store, calls } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-connect').click();
    flushSync();
    typeInto('.code-field', '849201');
    query<HTMLButtonElement>('.import-data').click();
    await tick();

    expect(calls.confirmPairingCode).toEqual(['849201']);
    // Kotlin calls `onDismiss()` on success, so only the main dialog remains.
    expect(target.querySelector('[data-testid=sync-connect-dialog]')).toBeNull();
    expect(target.querySelector('[data-testid=sync-dialog]')).not.toBeNull();
  });

  it('reports a rejected code in the error banner and stays open', async () => {
    const { store } = makeStore({
      confirm: async () => {
        throw new Error('유효하지 않거나 만료된 코드입니다.');
      },
    });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-connect').click();
    flushSync();
    typeInto('.code-field', '000000');
    query<HTMLButtonElement>('.import-data').click();
    await tick();

    const banner = query('.connect-dialog .banner.error');
    expect(banner.getAttribute('role')).toBe('alert');
    expect(banner.textContent).toContain('유효하지 않거나 만료된 코드입니다.');
    expect(target.querySelector('[data-testid=sync-connect-dialog]')).not.toBeNull();
  });

  it('switches to master-key mode, uppercases the key and installs it', async () => {
    const { store, calls } = makeStore({ installKey: 'C8-7X9K-2M4Q-99PL' });
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-connect').click();
    flushSync();

    query<HTMLButtonElement>('.switch-mode').click();
    flushSync();

    expect(query('.connect-dialog .dialog-title').textContent).toBe('마스터 복구 키로 연결');
    expect(query('.connect-dialog .desc').textContent).toBe(
      '이전에 저장해 둔 마스터 복구 키(C8-XXXX-XXXX-XXXX)를 입력하여 데이터를 직접 복원합니다.',
    );
    const field = query<HTMLInputElement>('.key-field');
    expect(field.getAttribute('placeholder')).toBe('예: C8-7X9K-2M4Q-99PL');
    expect(query<HTMLButtonElement>('.import-data').disabled).toBe(true);

    // `onValueChange = { it.trim().uppercase() }`.
    typeInto('.key-field', ' c8-7x9k-2m4q-99pl ');
    expect(query<HTMLInputElement>('.key-field').value).toBe('C8-7X9K-2M4Q-99PL');

    query<HTMLButtonElement>('.import-data').click();
    await tick();

    expect(calls.setSyncKey).toEqual(['C8-7X9K-2M4Q-99PL']);
    expect(target.querySelector('[data-testid=sync-connect-dialog]')).toBeNull();
    // The main dialog now shows the installed key in the advanced card.
    query<HTMLButtonElement>('.fold-row').click();
    flushSync();
    expect(query('.advanced-key').textContent).toBe('C8-7X9K-2M4Q-99PL');
  });

  it('switches back from master-key mode and cancels with actionCancel', async () => {
    const { store } = makeStore();
    render(SyncDialog, { store, onDismiss: vi.fn() });
    await tick();

    query<HTMLButtonElement>('.open-connect').click();
    flushSync();

    expect(query('.switch-mode').textContent).toContain(
      '기존 기기가 없나요? 마스터 복구 키로 복원',
    );
    query<HTMLButtonElement>('.switch-mode').click();
    flushSync();
    expect(query('.switch-mode').textContent).toContain('6자리 페어링 코드로 입력하기');

    query<HTMLButtonElement>('.switch-mode').click();
    flushSync();
    expect(query<HTMLInputElement>('.code-field').getAttribute('placeholder')).toBe('예: 849201');

    buttonByText('취소').click();
    flushSync();
    expect(target.querySelector('[data-testid=sync-connect-dialog]')).toBeNull();
  });
});


describe('SyncDialog — reader coexistence', () => {
  it('consumes Escape so closing the modal does not also leave the reader', async () => {
    const shell = new DesktopStore({ persistPreferences: false });
    shell.screen = 'Reader';
    shell.currentEpisode = { wrId: 'e1', title: '1화', href: '' };

    render(ReaderPane, { store: shell });
    await tick();

    const onDismiss = vi.fn();
    const { store } = makeStore();
    render(SyncDialog, { store, onDismiss });
    await tick();

    // ReaderPane listens on `window` (bubble); the dialog listens on the capture phase.
    window.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    await tick();

    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(shell.screen).toBe('Reader');
  });
});
