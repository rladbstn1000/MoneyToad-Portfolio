import axios from 'axios';
import { authMode } from './authMode';
import { demoRateLimitDelay } from './demoRateLimit';
import type { AuthState } from '../store/authStore';
import { useAuthStore } from '../store/authStore';
import { demoHttp } from '../api/services/demoAuth';
import { cancelDemoReadiness, checkDemoReadyOnce, DemoReadinessError, DemoStartupSlowError, waitForDemoReady } from './demoReadiness';

export class DemoAuthError extends Error {
  readonly code: number | undefined;
  constructor(message: string, code?: number) { super(message); this.name = 'DemoAuthError'; this.code = code; }
}

// Only a definite login rejection can offer another login instead of restoring
// an exchange whose database or cookie outcome may be unknown.
class DemoAdmissionError extends DemoAuthError {}
class DemoLoginLimitError extends DemoAuthError {
  readonly retryAt: number;
  constructor(retryAt: number) { super('잠시 후 다시 체험해 주세요.', 429); this.retryAt = retryAt; }
}

function admissionMessage(error: unknown): string | null {
  if (!axios.isAxiosError<unknown>(error) || error.response?.status !== 503) return null;
  const data = error.response.data;
  if (typeof data !== 'object' || data === null || Array.isArray(data) || !('code' in data)) return null;
  if (data.code === 'DEMO_CAPACITY_FULL') return '현재 체험 공간이 가득 찼습니다. 나중에 다시 시도해 주세요.';
  if (data.code === 'DEMO_ADMISSION_BUSY') return '다른 체험을 준비 중입니다. 잠시 후 다시 시도해 주세요.';
  return null;
}

let bootstrap: Promise<void> | undefined;
let flight: Promise<void> | undefined;
let logoutFlight: Promise<void> | undefined;
let manualReadinessFlight: Promise<void> | undefined;
let logoutToken: string | null = null;
const statusOf = (error: unknown) => axios.isAxiosError(error) ? error.response?.status : undefined;
const stale = () => new DemoAuthError('이전 체험의 요청입니다.');

export function expireDemo(): void {
  const state = useAuthStore.getState();
  if (state.expiresAt !== null && state.expiresAt <= Date.now()) endDemo('체험이 종료되었습니다.');
}

export function assertDemoGeneration(generation: number): void {
  expireDemo();
  if (useAuthStore.getState().generation !== generation) throw stale();
}

export function endDemo(message = '체험이 종료되었습니다.'): void {
  // A read-only warm-up can be cancelled; an ambiguous token POST is never resent.
  if (useAuthStore.getState().operation === 'readiness') flight = undefined;
  cancelDemoReadiness();
  manualReadinessFlight = undefined;
  logoutToken = null;
  useAuthStore.setState(state => ({ accessToken: null, expiresAt: null,
    status: 'anonymous', operation: null, generation: state.generation + 1, message, recovery: 'restore' }));
}

function failure(error: unknown, generation: number, recovery: 'restore' | 'logout', prior?: AuthState): never {
  assertDemoGeneration(generation);
  if (error instanceof DemoLoginLimitError && prior) {
    // A definite pre-creation rejection neither logs out an existing visitor nor
    // invalidates cookies. Preserve any prior token while publishing only a cooldown.
    useAuthStore.setState({ accessToken: prior.accessToken, expiresAt: prior.expiresAt,
      status: prior.status === 'restoring' ? 'anonymous' : prior.status, operation: null,
      message: error.message, loginRetryAt: error.retryAt, recovery: prior.recovery });
    throw error;
  }
  if (error instanceof DemoAdmissionError) {
    endDemo(error.message);
    throw error;
  }
  if (error instanceof DemoStartupSlowError && recovery === 'restore') {
    useAuthStore.setState({ status: 'startupSlow', operation: null, message: null,
      readinessRetryAt: error.readinessRetryAt !== null && error.readinessRetryAt > Date.now() ? error.readinessRetryAt : null });
    throw new DemoAuthError(error.message);
  }
  const code = statusOf(error);
  if (code === 401) {
    endDemo('체험이 종료되었습니다. 다시 시작하려면 체험 버튼을 눌러 주세요.');
    throw new DemoAuthError('체험이 종료되었습니다.', 401);
  }
  const message = error instanceof DemoReadinessError ? error.message : code === 400 || code === 403 || code === 415
    ? '체험 요청 설정을 확인해 주세요.'
    : recovery === 'logout' ? '종료를 확인하지 못했습니다. 다시 시도해 주세요.'
      : '체험 연결을 확인하지 못했습니다. 다시 시도해 주세요.';
  useAuthStore.setState(state => ({ accessToken: null, expiresAt: null, status: 'unavailable',
    operation: null, generation: state.generation + 1, message, recovery }));
  throw new DemoAuthError(message, code);
}

async function exchange(action: 'login' | 'reissue', generation: number): Promise<void> {
  let token: string;
  try { token = await demoHttp.token(action); }
  catch (error) {
    assertDemoGeneration(generation);
    const delay = action === 'login' ? demoRateLimitDelay(error, 'DEMO_LOGIN_RATE_LIMITED') : null;
    if (delay !== null) throw new DemoLoginLimitError(Date.now() + delay);
    const message = action === 'login' ? admissionMessage(error) : null;
    if (message) throw new DemoAdmissionError(message, 503);
    if (action !== 'login' || statusOf(error) !== 409) throw error;
    token = await demoHttp.token('reissue');
  }
  assertDemoGeneration(generation);
  const expiresAt = await demoHttp.session(token);
  assertDemoGeneration(generation);
  const priorExpiry = useAuthStore.getState().expiresAt;
  if (priorExpiry !== null && expiresAt !== priorExpiry) throw new Error('Demo session lifetime changed');
  useAuthStore.setState(state => ({ accessToken: token, expiresAt, status: 'authenticated',
    operation: null, revision: state.revision + 1, message: null, recovery: 'restore' }));
}

function runExchange(action: 'login' | 'reissue', operation: 'login' | 'restore' | 'refresh'): Promise<void> {
  if (authMode !== 'demo') return Promise.reject(stale());
  if (logoutFlight) return Promise.reject(stale());
  if (flight) return flight;
  const prior = useAuthStore.getState();
  if (operation !== 'refresh') {
    useAuthStore.setState(state => ({ generation: state.generation + 1, accessToken: null,
      expiresAt: null, status: 'restoring', message: null, operation: 'readiness' }));
  } else useAuthStore.setState({ operation });
  const generation = useAuthStore.getState().generation;
  const work = (async () => {
    if (operation !== 'refresh') {
      await waitForDemoReady();
      assertDemoGeneration(generation);
      useAuthStore.setState({ operation });
    }
    assertDemoGeneration(generation);
    await exchange(action, generation);
  })().catch(error => failure(error, generation, 'restore', prior));
  flight = work;
  void work.finally(() => { if (flight === work) flight = undefined; }).catch(() => {});
  return work;
}

export function restoreDemo(): Promise<void> { return runExchange('reissue', 'restore'); }
export function bootstrapDemo(): Promise<void> {
  if (authMode !== 'demo') return Promise.resolve();
  bootstrap ??= restoreDemo();
  return bootstrap;
}
export function loginDemo(): Promise<void> {
  const state = useAuthStore.getState();
  if (state.status === 'authenticated') return Promise.resolve();
  if (state.status === 'startupSlow') return Promise.reject(new DemoAuthError('먼저 서버 상태를 다시 확인해 주세요.'));
  if (state.loginRetryAt !== null && state.loginRetryAt > Date.now()) return Promise.reject(new DemoLoginLimitError(state.loginRetryAt));
  return runExchange('login', 'login');
}
export function manualCheckDemoReady(): Promise<void> {
  const state = useAuthStore.getState();
  if (authMode !== 'demo' || state.status !== 'startupSlow' || logoutFlight) return Promise.reject(stale());
  if (manualReadinessFlight) return manualReadinessFlight;
  if (state.readinessRetryAt !== null && state.readinessRetryAt > Date.now()) {
    return Promise.reject(new DemoAuthError('잠시 후 서버 상태를 다시 확인해 주세요.', 429));
  }
  const generation = state.generation;
  const revision = state.revision;
  const assertCurrent = () => {
    assertDemoGeneration(generation);
    const current = useAuthStore.getState();
    if (current.revision !== revision || current.status !== 'startupSlow' || current.operation !== 'manualReadiness') throw stale();
  };
  useAuthStore.setState({ operation: 'manualReadiness' });
  const work = checkDemoReadyOnce().then(result => {
    assertCurrent();
    useAuthStore.setState({ status: result.ready ? 'anonymous' : 'startupSlow', operation: null,
      readinessRetryAt: result.retryAt,
      message: result.ready ? '서버 준비가 확인됐습니다. 체험 버튼을 눌러 시작해 주세요.' : null });
  }).catch(error => {
    assertCurrent();
    failure(error, generation, 'restore');
  });
  manualReadinessFlight = work;
  void work.finally(() => { if (manualReadinessFlight === work) manualReadinessFlight = undefined; }).catch(() => {});
  return work;
}

export function refreshDemo(generation: number): Promise<void> {
  assertDemoGeneration(generation);
  return runExchange('reissue', 'refresh');
}

export function logoutDemo(): Promise<void> {
  if (authMode !== 'demo') return Promise.reject(stale());
  if (logoutFlight) return logoutFlight;
  const recovering = useAuthStore.getState().status === 'unavailable';
  if (['readiness', 'manualReadiness'].includes(useAuthStore.getState().operation ?? '')) {
    cancelDemoReadiness();
    manualReadinessFlight = undefined;
  }
  logoutToken = useAuthStore.getState().accessToken ?? logoutToken;
  const pending = flight;
  useAuthStore.setState(state => ({ generation: state.generation + 1, accessToken: null,
    expiresAt: null, status: 'restoring', operation: recovering ? 'readiness' : 'logout', recovery: 'logout', message: null }));
  const generation = useAuthStore.getState().generation;
  const work = (async () => {
    // Do not cancel/restart an RT exchange whose cookie may already have rotated.
    await pending?.catch(() => {});
    assertDemoGeneration(generation);
    try {
      if (recovering) {
        await waitForDemoReady();
        assertDemoGeneration(generation);
        useAuthStore.setState({ operation: 'logout' });
      }
      if (logoutToken) {
        try { await demoHttp.logout(logoutToken); assertDemoGeneration(generation); endDemo('체험을 종료했습니다.'); return; }
        catch (error) { if (statusOf(error) !== 401) throw error; }
      }
      const token = await demoHttp.token('reissue');
      assertDemoGeneration(generation);
      await demoHttp.session(token);
      assertDemoGeneration(generation);
      await demoHttp.logout(token);
      assertDemoGeneration(generation);
      endDemo('체험을 종료했습니다.');
    } catch (error) {
      if (statusOf(error) === 401) { assertDemoGeneration(generation); endDemo('체험을 종료했습니다.'); return; }
      failure(error, generation, 'logout');
    }
  })();
  logoutFlight = work;
  void work.finally(() => { if (logoutFlight === work) logoutFlight = undefined; }).catch(() => {});
  return work;
}
