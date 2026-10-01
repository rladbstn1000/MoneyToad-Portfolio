import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

function connectionError(status?: number, code?: string, retry?: string) {
  const error = new AxiosError('Synthetic connection failure', status === undefined ? 'ERR_NETWORK' : undefined);
  if (status !== undefined) error.response = { status, statusText: '', data: code ? { code } : null,
    headers: retry === undefined ? {} : { 'retry-after': retry }, config: { headers: new AxiosHeaders() } };
  return error;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
let runtime: Awaited<ReturnType<typeof load>>;
async function load() {
  const coordinator = await import('../../src/auth/demoCoordinator');
  const readiness = await import('../../src/auth/demoReadiness');
  const { useAuthStore: store } = await import('../../src/store/authStore');
  const { demoHttp } = await import('../../src/api/services/demoAuth');
  const ready = vi.spyOn(demoHttp, 'ready').mockResolvedValue(true);
  const token = vi.spyOn(demoHttp, 'token').mockResolvedValue('synthetic-recovery-access');
  const session = vi.spyOn(demoHttp, 'session').mockResolvedValue(Date.now() + 3_600_000);
  const logout = vi.spyOn(demoHttp, 'logout').mockResolvedValue();
  return { ...coordinator, ...readiness, store, ready, token, session, logout };
}
beforeEach(async () => { vi.resetModules(); vi.useFakeTimers(); runtime = await load(); });
afterEach(() => { cleanup(); runtime.endDemo(); vi.useRealTimers(); });

async function exhaustedRestore() {
  runtime.ready.mockResolvedValue(false);
  const pending = runtime.bootstrapDemo().catch(error => error);
  await vi.advanceTimersByTimeAsync(240_000);
  await pending;
  runtime.ready.mockReset().mockResolvedValue(true);
}
async function statusUi() {
  const { default: Status } = await import('../../src/components/DemoAuthStatus');
  return render(<MemoryRouter><Status /></MemoryRouter>);
}

describe('automatic budget exhaustion', () => {
  it('enters startupSlow at the exact deadline with no pending polling timer or later automatic GET', async () => {
    runtime.ready.mockImplementation((_signal, timeout) => new Promise((_resolve, reject) => {
      setTimeout(() => reject(new AxiosError('Synthetic timeout', 'ECONNABORTED')), timeout);
    }));
    const work = runtime.bootstrapDemo().catch(error => error);
    await vi.advanceTimersByTimeAsync(239_999);
    expect(runtime.store.getState().status).toBe('restoring');
    await vi.advanceTimersByTimeAsync(1); await work;
    expect(runtime.store.getState()).toMatchObject({ status: 'startupSlow', operation: null });
    expect(vi.getTimerCount()).toBe(0);
    const count = runtime.ready.mock.calls.length;
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(runtime.ready).toHaveBeenCalledTimes(count);
    expect(runtime.token).not.toHaveBeenCalled();
    expect(runtime.session).not.toHaveBeenCalled();
  });
  it('keeps the independent 80-attempt cap without scheduling a new polling cycle', async () => {
    runtime.ready.mockResolvedValue(false);
    const work = runtime.restoreDemo().catch(error => error);
    await vi.advanceTimersByTimeAsync(237_000); await work;
    expect(runtime.ready).toHaveBeenCalledTimes(80);
    expect(runtime.store.getState().status).toBe('startupSlow');
    expect(vi.getTimerCount()).toBe(0);
  });
  it('keeps normal startup restore as reissue then session when ready arrives before deadline', async () => {
    vi.spyOn(performance, 'now').mockReturnValueOnce(0).mockReturnValue(239_999);
    await runtime.bootstrapDemo();
    expect(runtime.store.getState().status).toBe('authenticated');
    expect(runtime.token.mock.calls).toEqual([['reissue']]);
    expect(runtime.session).toHaveBeenCalledTimes(1);
  });
  it.each([240_000, 240_001])('does not authenticate from a ready result at or after deadline (%s)', async time => {
    vi.spyOn(performance, 'now').mockReturnValueOnce(0).mockReturnValueOnce(239_999).mockReturnValue(time);
    await runtime.bootstrapDemo().catch(() => {});
    expect(runtime.store.getState().status).toBe('startupSlow');
    expect(runtime.token).not.toHaveBeenCalled();
  });
  it('carries an unexpired automatic Retry-After into manual recovery without another GET', async () => {
    runtime.ready.mockRejectedValue(connectionError(429, 'DEMO_READINESS_RATE_LIMITED', '3600'));
    const start = Date.now(); const work = runtime.restoreDemo().catch(error => error);
    await vi.advanceTimersByTimeAsync(240_000); await work;
    expect(runtime.store.getState()).toMatchObject({ status: 'startupSlow', readinessRetryAt: start + 3_600_000 });
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('manual readiness is one GET and never an auth exchange', () => {
  it('shows delayed-start instructions and one successful manual click enables a separate login action', async () => {
    await exhaustedRestore(); await statusUi();
    expect(screen.getByRole('heading', { name: '서버를 시작하고 있습니다' })).toBeVisible();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '서버 다시 확인' })); });
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.ready.mock.calls[0][1]).toBe(10_000);
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(runtime.token).not.toHaveBeenCalled();
    expect(runtime.session).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(300_000); });
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.token).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })); });
    expect(runtime.token.mock.calls).toEqual([['login']]);
    expect(runtime.store.getState().status).toBe('authenticated');
  });
  it('shares manual work across concurrent calls, disables the button, and does not rerun on rerender', async () => {
    await exhaustedRestore(); const gate = deferred<boolean>(); runtime.ready.mockReturnValue(gate.promise);
    const view = await statusUi();
    let work!: Promise<void>;
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '서버 다시 확인' }));
      work = runtime.manualCheckDemoReady();
      expect(runtime.manualCheckDemoReady()).toBe(work);
    });
    expect(screen.getByRole('button', { name: '서버 확인 중…' })).toBeDisabled();
    view.rerender(<MemoryRouter><div /></MemoryRouter>);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    await act(async () => { gate.resolve(true); await work; });
    expect(runtime.token).not.toHaveBeenCalled();
  });
  it.each(['false', '503', 'network', 'timeout'] as const)('manual %s returns to startupSlow without automatic retry', async kind => {
    await exhaustedRestore();
    if (kind === 'false') runtime.ready.mockResolvedValue(false);
    else runtime.ready.mockRejectedValue(kind === 'timeout' ? new AxiosError('Synthetic timeout', 'ECONNABORTED') : connectionError(kind === '503' ? 503 : undefined));
    await runtime.manualCheckDemoReady();
    expect(runtime.store.getState()).toMatchObject({ status: 'startupSlow', operation: null });
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.token).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
  it.each([['DEMO_READINESS_RATE_LIMITED', '5', 5_000], ['DEMO_READINESS_RATE_LIMITED', 'invalid', 60_000]] as const)('manual429 respects separate cooldown (%s/%s)', async (code, retry, delay) => {
    await exhaustedRestore(); runtime.ready.mockRejectedValueOnce(connectionError(429, code, retry));
    runtime.store.setState({ loginRetryAt: Date.now() + 300_000 });
    const loginRetryAt = runtime.store.getState().loginRetryAt;
    await statusUi();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '서버 다시 확인' })); });
    expect(runtime.store.getState().readinessRetryAt).toBe(Date.now() + delay);
    expect(runtime.store.getState().loginRetryAt).toBe(loginRetryAt);
    expect(screen.getByRole('button')).toBeDisabled();
    await expect(runtime.manualCheckDemoReady()).rejects.toMatchObject({ code: 429 });
    await act(async () => { await vi.advanceTimersByTimeAsync(delay - 1); });
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(screen.getByRole('button')).toBeEnabled();
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    expect(runtime.ready).toHaveBeenCalledTimes(2);
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })).toBeDisabled();
    expect(runtime.token).not.toHaveBeenCalled();
  });
  it.each([403, 429])('definite non-retryable status %s keeps unavailable policy', async status => {
    await exhaustedRestore(); runtime.ready.mockRejectedValue(connectionError(status, 'UNRECOGNIZED'));
    await expect(runtime.manualCheckDemoReady()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
    expect(runtime.token).not.toHaveBeenCalled();
  });
  it.each(['BACKEND_NOT_CONFIGURED', 'GATEWAY_NOT_CONFIGURED'])('known Function configuration failure %s is not another cold-start wait', async errorCode => {
    await exhaustedRestore(); const error = connectionError(503); error.response!.data = { error: errorCode };
    runtime.ready.mockRejectedValue(error);
    await expect(runtime.manualCheckDemoReady()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
  });
  it.each(['generation', 'revision'] as const)('a stale manual response cannot replace a newer authenticated %s', async key => {
    await exhaustedRestore(); const gate = deferred<boolean>(); runtime.ready.mockReturnValue(gate.promise);
    const pending = runtime.manualCheckDemoReady().catch(error => error);
    runtime.store.setState(state => ({ [key]: state[key] + 1, status: 'authenticated', operation: null, accessToken: 'synthetic-new-visit', expiresAt: Date.now() + 60_000 }));
    gate.resolve(true); await pending;
    expect(runtime.store.getState()).toMatchObject({ status: 'authenticated', accessToken: 'synthetic-new-visit' });
  });
  it('endDemo aborts a pending manual request and a late result cannot restore the old state', async () => {
    await exhaustedRestore(); const gate = deferred<boolean>(); runtime.ready.mockReturnValue(gate.promise);
    const pending = runtime.manualCheckDemoReady().catch(error => error);
    runtime.endDemo();
    expect(runtime.ready.mock.calls[0][0].aborted).toBe(true);
    gate.resolve(true); await pending;
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(runtime.token).not.toHaveBeenCalled();
  });
  it('login after manual ready can recover an existing cookie through the unchanged409 reissue path', async () => {
    await exhaustedRestore(); await runtime.manualCheckDemoReady();
    runtime.token.mockRejectedValueOnce(connectionError(409));
    await runtime.loginDemo();
    expect(runtime.token.mock.calls).toEqual([['login'], ['reissue']]);
    expect(runtime.session).toHaveBeenCalledTimes(1);
    expect(runtime.store.getState().status).toBe('authenticated');
  });
  it('does not retain an expired login cooldown after a long manual recovery idle', async () => {
    await exhaustedRestore();
    runtime.store.setState({ loginRetryAt: Date.now() + 60_000 });
    await statusUi();
    // No readiness cooldown means this mounted UI has no active clock timer.
    await act(async () => { await vi.advanceTimersByTimeAsync(160_000); });
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '서버 다시 확인' })); });
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })).toBeEnabled();
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.token).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })); });
    expect(runtime.token.mock.calls).toEqual([['login']]);
  });
  it('blocks direct login calls while delayed, before readiness or any POST', async () => {
    await exhaustedRestore();
    await expect(runtime.loginDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.ready).not.toHaveBeenCalled();
    expect(runtime.token).not.toHaveBeenCalled();
    expect(runtime.store.getState().status).toBe('startupSlow');
  });
  it('preserves the existing401 taxonomy without retrying or automatically logging in', async () => {
    await exhaustedRestore(); runtime.ready.mockRejectedValue(connectionError(401));
    await expect(runtime.manualCheckDemoReady()).rejects.toMatchObject({ code: 401 });
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(runtime.token).not.toHaveBeenCalled();
    expect(runtime.ready).toHaveBeenCalledTimes(1);
  });
  it('logout recovery deadline remains unavailable instead of advertising a new login', async () => {
    await runtime.loginDemo(); runtime.logout.mockRejectedValueOnce(connectionError(503));
    await runtime.logoutDemo().catch(() => {});
    runtime.ready.mockResolvedValue(false);
    const pending = runtime.logoutDemo().catch(error => error);
    await vi.advanceTimersByTimeAsync(240_000); await pending;
    expect(runtime.store.getState()).toMatchObject({ status: 'unavailable', recovery: 'logout' });
  });
});
