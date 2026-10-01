import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

function unavailable(status?: number) {
  const error = new AxiosError('Synthetic connection failure', status ? undefined : 'ERR_NETWORK');
  if (status) error.response = { status, statusText: '', data: null, headers: {}, config: { headers: new AxiosHeaders() } };
  return error;
}

let runtime: Awaited<ReturnType<typeof loadRuntime>>;
async function loadRuntime() {
  const readiness = await import('../../src/auth/demoReadiness');
  const coordinator = await import('../../src/auth/demoCoordinator');
  const { useAuthStore: store } = await import('../../src/store/authStore');
  const { demoHttp } = await import('../../src/api/services/demoAuth');
  const ready = vi.spyOn(demoHttp, 'ready').mockResolvedValue(true);
  const token = vi.spyOn(demoHttp, 'token').mockResolvedValue('synthetic-ready-access');
  const session = vi.spyOn(demoHttp, 'session').mockResolvedValue(Date.now() + 3_600_000);
  const logout = vi.spyOn(demoHttp, 'logout').mockResolvedValue();
  return { ...readiness, ...coordinator, store, ready, token, session, logout };
}

beforeEach(async () => { vi.resetModules(); vi.useFakeTimers(); runtime = await loadRuntime(); });
afterEach(() => { runtime.endDemo(); vi.useRealTimers(); });

describe('bounded shared readiness GET', () => {
  it('shares the exact Promise and stops polling permanently after success', async () => {
    const first = runtime.waitForDemoReady();
    expect(runtime.waitForDemoReady()).toBe(first);
    await first;
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.ready.mock.calls[0][1]).toBe(10_000);
    expect(runtime.token).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
  it('retries only readiness GET until an actual ready result', async () => {
    runtime.ready.mockResolvedValueOnce(false).mockResolvedValueOnce(false);
    const work = runtime.waitForDemoReady();
    await vi.advanceTimersByTimeAsync(6_000);
    await work;
    expect(runtime.ready).toHaveBeenCalledTimes(3);
    expect(runtime.token).not.toHaveBeenCalled();
  });
  it.each([503, undefined])('can recover one transient GET failure (%s)', async status => {
    runtime.ready.mockRejectedValueOnce(unavailable(status));
    const work = runtime.waitForDemoReady();
    await vi.advanceTimersByTimeAsync(3_000);
    await work;
    expect(runtime.ready).toHaveBeenCalledTimes(2);
    expect(runtime.token).not.toHaveBeenCalled();
  });
  it('fails a forbidden readiness request without repeating it', async () => {
    const failure = unavailable(403); runtime.ready.mockRejectedValue(failure);
    await expect(runtime.waitForDemoReady()).rejects.toBe(failure);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('bounds slow request timeouts by a 240s total deadline without POST', async () => {
    runtime.ready.mockImplementation((_signal, timeout) => new Promise((_resolve, reject) => {
      setTimeout(() => reject(new AxiosError('Synthetic timeout', 'ECONNABORTED')), timeout);
    }));
    const result = runtime.waitForDemoReady().catch(error => error);
    await vi.advanceTimersByTimeAsync(240_000);
    expect(await result).toBeInstanceOf(runtime.DemoReadinessError);
    expect(runtime.ready.mock.calls.every(([, timeout]) => timeout > 0 && timeout <= 10_000)).toBe(true);
    expect(runtime.ready.mock.calls.at(-1)![1]).toBeLessThan(10_000);
    expect(runtime.token).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
  it('keeps the final fractional-millisecond request timeout positive instead of unlimited', async () => {
    vi.spyOn(performance, 'now').mockReturnValueOnce(0).mockReturnValue(239_999.5);
    await runtime.waitForDemoReady();
    expect(runtime.ready.mock.calls[0][1]).toBe(1);
  });
  it('also bounds fast not-ready responses to 80 attempts', async () => {
    runtime.ready.mockResolvedValue(false);
    const result = runtime.waitForDemoReady().catch(error => error);
    await vi.advanceTimersByTimeAsync(240_000);
    expect(await result).toBeInstanceOf(runtime.DemoReadinessError);
    expect(runtime.ready).toHaveBeenCalledTimes(80);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('cancels a pending delay with no subsequent GET', async () => {
    runtime.ready.mockResolvedValue(false);
    const result = runtime.waitForDemoReady().catch(error => error);
    await vi.advanceTimersByTimeAsync(0);
    runtime.cancelDemoReadiness();
    expect(await result).toBeInstanceOf(runtime.DemoReadinessError);
    await vi.advanceTimersByTimeAsync(240_000);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('aborts an active request and gives an explicit recovery a fresh Promise', async () => {
    runtime.ready.mockImplementationOnce(signal => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new AxiosError('Synthetic cancellation', 'ERR_CANCELED')), { once: true });
    }));
    const old = runtime.waitForDemoReady(); const result = old.catch(error => error);
    runtime.cancelDemoReadiness();
    const next = runtime.waitForDemoReady();
    expect(next).not.toBe(old);
    await next;
    expect(await result).toBeInstanceOf(runtime.DemoReadinessError);
    expect(runtime.ready.mock.calls[0][0].aborted).toBe(true);
    expect(runtime.ready).toHaveBeenCalledTimes(2);
  });
});

describe('readiness preserves the auth visit and POST boundaries', () => {
  it('concurrent initial restore and login share readiness and never auto login', async () => {
    const gate = deferred<boolean>(); runtime.ready.mockReturnValue(gate.promise);
    const restore = runtime.restoreDemo();
    expect(runtime.loginDemo()).toBe(restore);
    expect(runtime.store.getState().operation).toBe('readiness');
    expect(runtime.token).not.toHaveBeenCalled();
    gate.resolve(true); await restore;
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.token.mock.calls).toEqual([['reissue']]);
    expect(runtime.session).toHaveBeenCalledTimes(1);
  });
  it('a cancelled old visit cannot POST or overwrite a newer login when readiness arrives late', async () => {
    const oldGate = deferred<boolean>(); runtime.ready.mockReturnValueOnce(oldGate.promise);
    const old = runtime.restoreDemo().catch(error => error);
    runtime.endDemo();
    await runtime.loginDemo();
    const current = runtime.store.getState().generation;
    oldGate.resolve(true);
    expect(await old).toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.token.mock.calls).toEqual([['login']]);
    expect(runtime.store.getState().generation).toBe(current);
    expect(runtime.store.getState().status).toBe('authenticated');
  });
  it('readiness failure is unavailable and explicit recovery restores instead of creating a visitor', async () => {
    runtime.ready.mockRejectedValueOnce(unavailable(403));
    await expect(runtime.loginDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
    expect(runtime.token).not.toHaveBeenCalled();
    await runtime.restoreDemo();
    expect(runtime.token.mock.calls).toEqual([['reissue']]);
  });
  it('normal 401 refresh does not start a new cold-start poll', async () => {
    await runtime.loginDemo();
    await runtime.refreshDemo(runtime.store.getState().generation);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.token.mock.calls).toEqual([['login'], ['reissue']]);
  });
  it.each(['login', 'reissue'] as const)('%s timeout is unavailable with zero automatic POST retry', async action => {
    runtime.token.mockRejectedValue(new AxiosError('Synthetic timeout', 'ECONNABORTED'));
    const run = action === 'login' ? runtime.loginDemo : runtime.restoreDemo;
    await expect(run()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    await vi.advanceTimersByTimeAsync(240_000);
    expect(runtime.token.mock.calls).toEqual([[action]]);
    expect(runtime.store.getState().status).toBe('unavailable');
    expect(runtime.store.getState().accessToken).toBeNull();
  });
  it('explicit logout recovery waits for readiness before its one logout attempt', async () => {
    await runtime.loginDemo(); runtime.logout.mockRejectedValueOnce(unavailable(503));
    await expect(runtime.logoutDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    const gate = deferred<boolean>(); runtime.ready.mockReturnValueOnce(gate.promise);
    const recovery = runtime.logoutDemo();
    await vi.advanceTimersByTimeAsync(0);
    expect(runtime.store.getState().operation).toBe('readiness');
    expect(runtime.logout).toHaveBeenCalledTimes(1);
    gate.resolve(true); await recovery;
    expect(runtime.logout).toHaveBeenCalledTimes(2);
    expect(runtime.store.getState().status).toBe('anonymous');
  });
});
