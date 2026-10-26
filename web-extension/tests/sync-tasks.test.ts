/**
 * Debounced, single-flight sync runner (`SyncTaskRunner`).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SyncTaskRunner, type SyncRunResult } from "../src/sync/tasks";
import { emptyUpdates, type SyncStatus, type SyncUpdates } from "../src/sync/sync-client";

/** Only the two members the runner uses. */
type FakeClient = {
  getStatus(): SyncStatus;
  push(updates: SyncUpdates): Promise<{
    status: string;
    savedAt: number;
    userId: string;
    sizeBytes: number;
    favorites: number;
    history: number;
    readEpisodes: number;
    isPro: boolean;
  }>;
  pushes: SyncUpdates[];
  status: SyncStatus;
  pushBehaviour: "ok" | "fail" | "slow";
};

function fakeClient(options: { autoSync?: boolean; behaviour?: "ok" | "fail" | "slow" } = {}): FakeClient {
  const client = {
    pushes: [] as SyncUpdates[],
    status: {
      syncKey: "C8-TEST-TEST-TEST",
      serverUrl: "https://example.test",
      lastSyncedAt: 0,
      lastResult: "idle" as const,
      lastError: null,
      isPro: false,
      autoSyncEnabled: options.autoSync ?? true,
    } as SyncStatus,
    pushBehaviour: options.behaviour ?? "ok",
    getStatus() {
      return { ...client.status };
    },
    async push(updates: SyncUpdates) {
      client.pushes.push(updates);
      if (client.pushBehaviour === "fail") throw new Error("network down");
      if (client.pushBehaviour === "slow") {
        await new Promise((resolve) => setTimeout(resolve, 30));
      }
      return {
        status: "success",
        savedAt: 42,
        userId: "u",
        sizeBytes: 1,
        favorites: updates.favorites.length,
        history: updates.history.length,
        readEpisodes: updates.readEpisodes.length,
        isPro: false,
      };
    },
  };
  return client as unknown as FakeClient;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("SyncTaskRunner", () => {
  it("debounces repeated progress events into one push", async () => {
    const client = fakeClient();
    const runner = new SyncTaskRunner(client, {
      debounceMs: 100,
      collectUpdates: () => ({ ...emptyUpdates(), history: [{ toonId: "a" }] }),
    });

    runner.schedule();
    await vi.advanceTimersByTimeAsync(50);
    runner.schedule();
    await vi.advanceTimersByTimeAsync(50);
    runner.schedule();
    expect(client.pushes).toHaveLength(0);

    await vi.advanceTimersByTimeAsync(100);
    expect(client.pushes).toHaveLength(1);
    expect(client.pushes[0].history).toEqual([{ toonId: "a" }]);
    runner.dispose();
  });

  it("collects the payload at run time, not when scheduled", async () => {
    const client = fakeClient();
    let page = 1;
    const runner = new SyncTaskRunner(client, {
      debounceMs: 10,
      collectUpdates: () => ({
        ...emptyUpdates(),
        readEpisodes: [{ toonId: "a", wrId: "e", lastPage: page }],
      }),
    });

    runner.schedule();
    page = 9;
    await vi.advanceTimersByTimeAsync(10);
    expect(client.pushes[0].readEpisodes[0].lastPage).toBe(9);
    runner.dispose();
  });

  it("does nothing when auto-sync is disabled", async () => {
    const client = fakeClient({ autoSync: false });
    const runner = new SyncTaskRunner(client, { debounceMs: 10, collectUpdates: () => emptyUpdates() });

    runner.schedule();
    await vi.advanceTimersByTimeAsync(100);
    expect(client.pushes).toHaveLength(0);

    // Explicit runs still work.
    await runner.run();
    expect(client.pushes).toHaveLength(1);
    runner.dispose();
  });

  it("coalesces overlapping runs instead of duplicating pushes", async () => {
    const client = fakeClient({ behaviour: "slow" });
    const runner = new SyncTaskRunner(client, { debounceMs: 10, collectUpdates: () => emptyUpdates() });

    const first = runner.run();
    const second = await runner.run();
    expect(second).toMatchObject({ ok: false, skipped: true });

    await vi.advanceTimersByTimeAsync(40);
    await first;
    expect(client.pushes).toHaveLength(1);
    runner.dispose();
  });

  it("reports the outcome of a successful push", async () => {
    const results: SyncRunResult[] = [];
    const client = fakeClient();
    const runner = new SyncTaskRunner(client, {
      debounceMs: 10,
      collectUpdates: () => ({ ...emptyUpdates(), favorites: [{ id: "a" }] }),
      onResult: (result) => results.push(result),
    });

    const result = await runner.run();
    expect(result).toEqual({ ok: true, savedAt: 42, favorites: 1, history: 0, readEpisodes: 0 });
    expect(results).toHaveLength(1);
    runner.dispose();
  });

  it("reports failures without throwing", async () => {
    const client = fakeClient({ behaviour: "fail" });
    const runner = new SyncTaskRunner(client, { debounceMs: 10, collectUpdates: () => emptyUpdates() });

    const result = await runner.run();
    expect(result).toEqual({ ok: false, error: "network down" });
    runner.dispose();
  });

  it("supports async collectors", async () => {
    const client = fakeClient();
    const runner = new SyncTaskRunner(client, {
      debounceMs: 10,
      collectUpdates: async () => {
        await Promise.resolve();
        return { ...emptyUpdates(), favorites: [{ id: "async" }] };
      },
    });

    await runner.run();
    expect(client.pushes[0].favorites).toEqual([{ id: "async" }]);
    runner.dispose();
  });

  it("cancels a pending run", async () => {
    const client = fakeClient();
    const runner = new SyncTaskRunner(client, { debounceMs: 50, collectUpdates: () => emptyUpdates() });

    runner.schedule();
    runner.cancel();
    await vi.advanceTimersByTimeAsync(100);
    expect(client.pushes).toHaveLength(0);
    runner.dispose();
  });

  it("stops scheduling after dispose", async () => {
    const client = fakeClient();
    const runner = new SyncTaskRunner(client, { debounceMs: 10, collectUpdates: () => emptyUpdates() });

    runner.schedule();
    runner.dispose();
    await vi.advanceTimersByTimeAsync(100);
    expect(client.pushes).toHaveLength(0);

    await expect(runner.run()).resolves.toMatchObject({ ok: false, skipped: true });
  });

  it("uses the documented 2.5s default debounce", async () => {
    const client = fakeClient();
    const runner = new SyncTaskRunner(client, { collectUpdates: () => emptyUpdates() });

    runner.schedule();
    await vi.advanceTimersByTimeAsync(2_499);
    expect(client.pushes).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(1);
    expect(client.pushes).toHaveLength(1);
    runner.dispose();
  });
});
