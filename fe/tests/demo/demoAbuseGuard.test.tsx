import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

function rejected(status = 429, code = 'DEMO_LOGIN_RATE_LIMITED', retry: string | null = '5') {
  const error = new AxiosError('Synthetic rejection');
  error.response = { status, statusText: '', data: { code }, headers: retry === null ? {} : { 'retry-after': retry },
    config: { headers: new AxiosHeaders() } };
  return error;
}
let runtime: Awaited<ReturnType<typeof load>>;
async function load() {
  const coordinator = await import('../../src/auth/demoCoordinator');
  const readiness = await import('../../src/auth/demoReadiness');
  const { useAuthStore: store } = await import('../../src/store/authStore');
  const { demoHttp } = await import('../../src/api/services/demoAuth');
  const ready = vi.spyOn(demoHttp, 'ready').mockResolvedValue(true);
  const token = vi.spyOn(demoHttp, 'token').mockResolvedValue('synthetic-abuse-access');
  const session = vi.spyOn(demoHttp, 'session').mockResolvedValue(Date.now() + 3_600_000);
  const logout = vi.spyOn(demoHttp, 'logout').mockResolvedValue();
  return { ...coordinator, ...readiness, store, ready, token, session, logout };
}
beforeEach(async () => { vi.resetModules(); vi.useFakeTimers(); runtime = await load(); runtime.store.setState({ status: 'anonymous' }); });
afterEach(() => { cleanup(); runtime.endDemo(); vi.useRealTimers(); });

describe('only direct login 429 creates a memory cooldown', () => {
  it('blocks calls before readiness and enables manual retry without any automatic POST', async () => {
    runtime.token.mockRejectedValueOnce(rejected());
    await expect(runtime.loginDemo()).rejects.toMatchObject({ code: 429 });
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(runtime.store.getState().message).toBe('잠시 후 다시 체험해 주세요.');
    const started = Date.now();
    expect(runtime.store.getState()).toMatchObject({ loginRetryAt: started + 5_000 });
    await expect(runtime.loginDemo()).rejects.toMatchObject({ code: 429 });
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(runtime.token).toHaveBeenCalledTimes(1);
    await runtime.loginDemo();
    expect(runtime.token.mock.calls).toEqual([['login'], ['login']]);
    expect(runtime.store.getState().status).toBe('authenticated');
  });
  it.each([undefined, '0', '-1', '3601', '1.5', '1e2', 'Mon, 01 Jan 2035 00:00:00 GMT'])('uses bounded fallback for invalid Retry-After (%s)', async retry => {
    runtime.token.mockRejectedValueOnce(rejected(429, 'DEMO_LOGIN_RATE_LIMITED', retry ?? null));
    await runtime.loginDemo().catch(() => {});
    expect(runtime.store.getState()).toMatchObject({ loginRetryAt: Date.now() + 60_000 });
  });
  it.each([['OTHER_CODE', 429], ['DEMO_LOGIN_RATE_LIMITED', 503]] as const)('does not treat %s/%s as a definite login rejection', async (code, status) => {
    runtime.token.mockRejectedValueOnce(rejected(status, code));
    await runtime.loginDemo().catch(() => {});
    expect(runtime.store.getState()).toMatchObject({ status: 'unavailable', loginRetryAt: null });
  });
  it('does not reinterpret 409 fallback reissue 429 as direct login rejection', async () => {
    runtime.token.mockRejectedValueOnce(rejected(409)).mockRejectedValueOnce(rejected());
    await runtime.loginDemo().catch(() => {});
    expect(runtime.token.mock.calls).toEqual([['login'], ['reissue']]);
    expect(runtime.store.getState()).toMatchObject({ status: 'unavailable', loginRetryAt: null });
  });
  it('restore/session/logout remain available during login cooldown', async () => {
    runtime.token.mockRejectedValueOnce(rejected()); await runtime.loginDemo().catch(() => {});
    await runtime.restoreDemo();
    await runtime.refreshDemo(runtime.store.getState().generation);
    await runtime.logoutDemo();
    expect(runtime.token.mock.calls).toEqual([['login'], ['reissue'], ['reissue']]);
    expect(runtime.session).toHaveBeenCalledTimes(2);
    expect(runtime.logout).toHaveBeenCalledTimes(1);
  });
  it('authenticated login is a no-op and preserves the current token despite cooldown', async () => {
    await runtime.loginDemo();
    const before = runtime.store.getState();
    // An active browser session is independent of a remembered visitor-creation cooldown.
    runtime.store.setState({ loginRetryAt: Date.now() + 60_000 });
    await runtime.loginDemo();
    expect(runtime.store.getState().accessToken).toBe(before.accessToken);
    expect(runtime.token).toHaveBeenCalledTimes(1);
  });
  it('a late previous-generation 429 cannot overwrite a newer authenticated visit', async () => {
    let reject!: (error: unknown) => void;
    runtime.token.mockReturnValueOnce(new Promise((_resolve, no) => { reject = no; }));
    const pending = runtime.loginDemo().catch(error => error);
    await vi.advanceTimersByTimeAsync(0);
    runtime.store.setState(state => ({ generation: state.generation + 1, status: 'authenticated', accessToken: 'synthetic-new-visit', expiresAt: Date.now() + 60_000 }));
    reject(rejected()); await pending;
    expect(runtime.store.getState()).toMatchObject({ status: 'authenticated', accessToken: 'synthetic-new-visit', loginRetryAt: null });
  });
  it('the visible button unlocks without issuing any request, then allows one manual click', async () => {
    const { default: Status } = await import('../../src/components/DemoAuthStatus');
    runtime.token.mockRejectedValueOnce(rejected());
    render(<MemoryRouter><Status /></MemoryRouter>);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })); });
    expect(screen.getByRole('button')).toBeDisabled();
    expect(screen.getByText('잠시 후 다시 체험해 주세요.')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000); });
    expect(screen.getByRole('button')).toBeEnabled();
    expect(runtime.token).toHaveBeenCalledTimes(1);
    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    expect(runtime.token).toHaveBeenCalledTimes(2);
  });
});

describe('readiness 429 is separate and stays within the existing deadline', () => {
  it('waits Retry-After and sends only GET until actual readiness', async () => {
    runtime.ready.mockRejectedValueOnce(rejected(429, 'DEMO_READINESS_RATE_LIMITED', '7'));
    const pending = runtime.waitForDemoReady();
    await vi.advanceTimersByTimeAsync(6_999); expect(runtime.ready).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1); await pending;
    expect(runtime.ready).toHaveBeenCalledTimes(2);
    expect(runtime.token).not.toHaveBeenCalled();
    expect(runtime.store.getState()).toMatchObject({ loginRetryAt: null });
  });
  it('does not schedule a new request beyond the unchanged 240s deadline', async () => {
    runtime.ready.mockRejectedValue(rejected(429, 'DEMO_READINESS_RATE_LIMITED', '3600'));
    const pending = runtime.waitForDemoReady().catch(error => error);
    await vi.advanceTimersByTimeAsync(240_000);
    expect(await pending).toBeInstanceOf(runtime.DemoReadinessError);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
    expect(runtime.token).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
  it('unknown 429 is not automatically retried', async () => {
    const error = rejected(); runtime.ready.mockRejectedValue(error);
    await expect(runtime.waitForDemoReady()).rejects.toBe(error);
    await vi.advanceTimersByTimeAsync(240_000);
    expect(runtime.ready).toHaveBeenCalledTimes(1);
  });
});
