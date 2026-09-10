import axios from 'axios';
import { authMode } from './authMode';
import { useAuthStore } from '../store/authStore';
import { demoHttp } from '../api/services/demoAuth';

export class DemoAuthError extends Error {
  readonly code: number | undefined;
  constructor(message: string, code?: number) { super(message); this.name = 'DemoAuthError'; this.code = code; }
}

let bootstrap: Promise<void> | undefined;
let flight: Promise<void> | undefined;
let logoutFlight: Promise<void> | undefined;
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
  logoutToken = null;
  useAuthStore.setState(state => ({ accessToken: null, expiresAt: null,
    status: 'anonymous', operation: null, generation: state.generation + 1, message, recovery: 'restore' }));
}

function failure(error: unknown, generation: number, recovery: 'restore' | 'logout'): never {
  assertDemoGeneration(generation);
  const code = statusOf(error);
  if (code === 401) {
    endDemo('체험이 종료되었습니다. 다시 시작하려면 체험 버튼을 눌러 주세요.');
    throw new DemoAuthError('체험이 종료되었습니다.', 401);
  }
  const message = code === 400 || code === 403 || code === 415
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
  if (operation !== 'refresh') {
    useAuthStore.setState(state => ({ generation: state.generation + 1, accessToken: null,
      expiresAt: null, status: 'restoring', message: null, operation }));
  } else useAuthStore.setState({ operation });
  const generation = useAuthStore.getState().generation;
  const work = exchange(action, generation).catch(error => failure(error, generation, 'restore'));
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
  if (useAuthStore.getState().status === 'authenticated') return Promise.resolve();
  return runExchange('login', 'login');
}
export function refreshDemo(generation: number): Promise<void> {
  assertDemoGeneration(generation);
  return runExchange('reissue', 'refresh');
}

export function logoutDemo(): Promise<void> {
  if (authMode !== 'demo') return Promise.reject(stale());
  if (logoutFlight) return logoutFlight;
  logoutToken = useAuthStore.getState().accessToken ?? logoutToken;
  const pending = flight;
  useAuthStore.setState(state => ({ generation: state.generation + 1, accessToken: null,
    expiresAt: null, status: 'restoring', operation: 'logout', recovery: 'logout', message: null }));
  const generation = useAuthStore.getState().generation;
  const work = (async () => {
    // Do not cancel/restart an RT exchange whose cookie may already have rotated.
    await pending?.catch(() => {});
    assertDemoGeneration(generation);
    try {
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
