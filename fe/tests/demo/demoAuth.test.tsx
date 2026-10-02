import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('recharts', () => import('../rechartsDouble'));
vi.mock('lottie-react', () => ({ default: () => null }));

const ORIGIN = 'http://127.0.0.1:18080';
type Reply = () => Response | Promise<Response>;
type Record = { path: string; method: string; json: boolean; header: boolean; credentials: boolean; body: string };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
let requests: Record[];
let unhandled: number;
let tokenSequence: number;
let expiry: string;
let readyReply: Reply;
let loginReply: Reply;
let refreshReply: Reply;
let sessionReply: Reply;
let logoutReply: Reply;
let businessReply: (request: Request) => Response | Promise<Response>;
const okToken = () => HttpResponse.json({ accessToken: `synthetic-access-${++tokenSequence}` });
const count = (part: string) => requests.filter(row => row.path.includes(part)).length;
async function record(request: Request, reply: Reply) {
  requests.push({ path: new URL(request.url).pathname, method: request.method,
    json: request.headers.get('content-type')?.startsWith('application/json') ?? false,
    header: request.headers.get('x-moneytoad-demo') === '1',
    credentials: request.credentials === 'include', body: request.method === 'POST' ? await request.text() : '' });
  return reply();
}
const server = setupServer(
  http.get(`${ORIGIN}/api/auth/demo/ready`, ({ request }) => record(request, readyReply)),
  http.post(`${ORIGIN}/api/auth/demo/login`, ({ request }) => record(request, loginReply)),
  http.post(`${ORIGIN}/api/auth/demo/reissue`, ({ request }) => record(request, refreshReply)),
  http.get(`${ORIGIN}/api/auth/demo/session`, ({ request }) => record(request, sessionReply)),
  http.post(`${ORIGIN}/api/auth/demo/logout`, ({ request }) => record(request, logoutReply)),
  http.all(`${ORIGIN}/api/*`, ({ request }) => {
    const path = new URL(request.url).pathname;
    if (!/^\/api\/(test\/|users$|budgets(?:\/|$)|transactions(?:\/|$))/.test(path)) {
      unhandled++;
      return new HttpResponse(null, { status: 500 });
    }
    return record(request, () => businessReply(request));
  }),
  http.get(`${ORIGIN}/*.json`, () => HttpResponse.json({})),
);
let runtime: Awaited<ReturnType<typeof loadRuntime>>;
async function loadRuntime() {
  const coordinator = await import('../../src/auth/demoCoordinator');
  const { useAuthStore } = await import('../../src/store/authStore');
  const client = await import('../../src/api/client');
  const cache = await import('../../src/auth/demoQueryClient');
  return { ...coordinator, store: useAuthStore, ...client, ...cache };
}
beforeAll(() => server.listen({ onUnhandledRequest() { unhandled++; throw new Error('Unexpected external request'); } }));
afterAll(() => server.close());
beforeEach(async () => {
  vi.resetModules();
  requests = []; unhandled = 0; tokenSequence = 0;
  expiry = new Date(Date.now() + 3600000).toISOString();
  readyReply = () => HttpResponse.json({ ready: true });
  loginReply = okToken; refreshReply = okToken;
  sessionReply = () => HttpResponse.json({ demo: true, expiresAt: expiry });
  logoutReply = () => new HttpResponse(null, { status: 204 });
  businessReply = request => {
    const path = new URL(request.url).pathname;
    if (path === '/api/users') return HttpResponse.json({ id: 123, name: '합성 방문자' });
    if (path === '/api/transactions') return HttpResponse.json(Array.from({ length: 12 }, (_, index) => ({
      date: `2025-${String(index + 1).padStart(2, '0')}`, totalAmount: 0, leaked: false,
    })));
    return HttpResponse.json([]);
  };
  localStorage.setItem('accessToken', JSON.stringify({ state: { accessToken: 'oauth-preserved' }, version: 1 }));
  runtime = await loadRuntime();
});
afterEach(() => {
  cleanup();
  runtime.endDemo();
  server.resetHandlers();
  vi.useRealTimers();
  expect(unhandled).toBe(0);
});
async function renderApp(path = '/chart') {
  const { default: Provider } = await import('../../src/auth/AuthProvider');
  const { default: App } = await import('../../src/App');
  return render(<StrictMode><MemoryRouter initialEntries={[path]}><Provider><App /></Provider></MemoryRouter></StrictMode>);
}
async function loggedIn() { await runtime.loginDemo(); }

describe('demo memory and wire boundary', () => {
  it('keeps demo AT in memory and leaves OAuth storage byte-identical through login, refresh and logout', async () => {
    const before = localStorage.getItem('accessToken');
    expect(runtime.store.getState().accessToken).toBeNull();
    await loggedIn();
    await runtime.refreshDemo(runtime.store.getState().generation);
    await runtime.logoutDemo();
    expect(localStorage.getItem('accessToken') === before).toBe(true);
    expect(localStorage.length).toBe(1);
    expect(runtime.store.getState().accessToken).toBeNull();
    expect(Object.keys(runtime.store.getState()).some(key => /^(sid|userId|refreshToken)$/.test(key))).toBe(false);
  });
  it('sends actual empty JSON, demo header and credentials for every POST; session is bodyless', async () => {
    await loggedIn(); await runtime.refreshDemo(runtime.store.getState().generation); await runtime.logoutDemo();
    const posts = requests.filter(row => row.method === 'POST');
    expect(posts.map(row => row.path.split('/').pop())).toEqual(['login', 'reissue', 'logout']);
    expect(posts.every(row => row.body === '{}' && row.json && row.header && row.credentials)).toBe(true);
    expect(requests.filter(row => row.path.endsWith('/session')).every(row => row.body === '' && row.credentials)).toBe(true);
  });
  it('does not authenticate until session confirmation finishes', async () => {
    const gate = deferred<Response>(); sessionReply = () => gate.promise;
    const login = runtime.loginDemo();
    await waitFor(() => expect(count('/session')).toBe(1));
    expect(runtime.store.getState().status).toBe('restoring');
    expect(runtime.store.getState().accessToken).toBeNull();
    gate.resolve(HttpResponse.json({ demo: true, expiresAt: expiry })); await login;
    expect(runtime.store.getState().status).toBe('authenticated');
  });
  it('rejects a malformed session response without publishing a token', async () => {
    sessionReply = () => HttpResponse.json({ demo: false, expiresAt: expiry });
    await expect(runtime.loginDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
    expect(runtime.store.getState().accessToken).toBeNull();
  });
  it('login409 restores the cookie session without issuing another login', async () => {
    loginReply = () => new HttpResponse(null, { status: 409 }); await loggedIn();
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([1, 1, 1]);
    expect(runtime.store.getState().status).toBe('authenticated');
  });
  it('login401 ends anonymous and does not automatically retry', async () => {
    loginReply = () => new HttpResponse(null, { status: 401 });
    await expect(runtime.loginDemo()).rejects.toMatchObject({ code: 401 });
    expect(runtime.store.getState().status).toBe('anonymous');
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([1, 0, 0]);
  });
  it.each(['503', 'network'] as const)('login %s is unavailable, recovery restores rather than creating another user', async kind => {
    loginReply = () => kind === '503' ? new HttpResponse(null, { status: 503 }) : HttpResponse.error();
    await expect(runtime.loginDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
    await runtime.restoreDemo();
    expect(runtime.store.getState().status).toBe('authenticated');
    expect(count('/login')).toBe(1);
  });
});

describe('explicit login admission failures', () => {
  const admissionCases = [
    { code: 'DEMO_CAPACITY_FULL', message: '현재 체험 공간이 가득 찼습니다. 나중에 다시 시도해 주세요.' },
    { code: 'DEMO_ADMISSION_BUSY', message: '다른 체험을 준비 중입니다. 잠시 후 다시 시도해 주세요.' },
  ];
  const admissionReply = (code: string, status = 503) => HttpResponse.json({
    status, error: 'Service Unavailable', message: 'Server message is not displayed', code,
  }, { status });

  it.each(admissionCases)('$code shows the landing message and needs a second explicit click to login', async ({ code, message }) => {
    refreshReply = () => new HttpResponse(null, { status: 401 });
    await renderApp('/');
    const beforeStorage = localStorage.getItem('accessToken');
    loginReply = () => admissionReply(code);
    fireEvent.click(await screen.findByRole('button', { name: '샘플 데이터로 체험하기' }));
    await screen.findByText(message);
    expect(runtime.store.getState()).toMatchObject({
      status: 'anonymous', operation: null, accessToken: null, expiresAt: null,
    });
    expect(localStorage.getItem('accessToken')).toBe(beforeStorage);
    expect(screen.queryByRole('navigation', { name: '체험 메뉴' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '연결 다시 시도' })).not.toBeInTheDocument();
    expect(screen.queryByText('Server message is not displayed')).not.toBeInTheDocument();
    expect([count('/login'), count('/reissue'), count('/session'), count('/users'), count('/transactions')]).toEqual([1, 1, 0, 0, 0]);
    loginReply = okToken;
    fireEvent.click(screen.getByRole('button', { name: '샘플 데이터로 체험하기' }));
    await screen.findByRole('navigation', { name: '체험 메뉴' });
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([2, 1, 1]);
  });

  it.each([
    { label: 'generic503', data: null, status: 503 },
    { label: 'integrity unavailable', data: { code: 'DEMO_ADMISSION_UNAVAILABLE' }, status: 503 },
    { label: 'unknown code', data: { code: 'OTHER_ERROR' }, status: 503 },
    { label: 'wrong case', data: { code: 'demo_capacity_full' }, status: 503 },
    { label: 'array body', data: [{ code: 'DEMO_CAPACITY_FULL' }], status: 503 },
    { label: 'message-only code', data: { message: 'DEMO_CAPACITY_FULL' }, status: 503 },
    { label: 'wrong status', data: { code: 'DEMO_CAPACITY_FULL' }, status: 500 },
  ])('$label preserves unavailable and recovers through reissue, never another automatic login', async ({ data, status }) => {
    loginReply = () => HttpResponse.json(data, { status });
    await expect(runtime.loginDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState()).toMatchObject({ status: 'unavailable', recovery: 'restore', accessToken: null });
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([1, 0, 0]);
    await runtime.restoreDemo();
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([1, 1, 1]);
    expect(runtime.store.getState().status).toBe('authenticated');
  });

  it.each(admissionCases)('$code from reissue remains unavailable', async ({ code }) => {
    refreshReply = () => admissionReply(code);
    await expect(runtime.restoreDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([0, 1, 0]);
  });

  it.each(admissionCases)('$code from login409 fallback reissue remains unavailable', async ({ code }) => {
    loginReply = () => new HttpResponse(null, { status: 409 });
    refreshReply = () => admissionReply(code);
    await expect(runtime.loginDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([1, 1, 0]);
  });

  it.each(admissionCases)('$code from session confirmation remains unavailable', async ({ code }) => {
    sessionReply = () => admissionReply(code);
    await expect(runtime.loginDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState()).toMatchObject({ status: 'unavailable', accessToken: null });
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([1, 0, 1]);
  });

  it.each(admissionCases)('late $code cannot replace a newer visit message', async ({ code }) => {
    const gate = deferred<Response>(); loginReply = () => gate.promise;
    const work = runtime.loginDemo().catch(error => error);
    await waitFor(() => expect(count('/login')).toBe(1));
    runtime.endDemo('현재 방문 안내');
    const generation = runtime.store.getState().generation;
    gate.resolve(admissionReply(code));
    expect(await work).toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState()).toMatchObject({
      generation, status: 'anonymous', message: '현재 방문 안내', accessToken: null,
    });
    expect([count('/login'), count('/reissue'), count('/session')]).toEqual([1, 0, 0]);
  });
});

describe('single-flight across actual main and AI Axios transports', () => {
  it('shares one refresh and retries every request exactly once', async () => {
    await loggedIn(); const oldToken = runtime.store.getState().accessToken;
    businessReply = request => request.headers.get('authorization') === `Bearer ${oldToken}`
      ? new HttpResponse(null, { status: 401 }) : HttpResponse.json({ ok: true });
    const gate = deferred<Response>(); refreshReply = () => gate.promise;
    const jobs = [runtime.request({ url: '/api/test/main' }), runtime.aiRequest({ url: '/api/test/ai' }), runtime.request({ url: '/api/test/main2' })];
    await waitFor(() => expect(count('/api/test/')).toBe(3));
    await waitFor(() => expect(count('/reissue')).toBe(1));
    gate.resolve(okToken()); await Promise.all(jobs);
    expect(count('/reissue')).toBe(1); expect(count('/api/test/')).toBe(6);
  });
  it('uses the latest AT for a late old-token401 without rotating RT again', async () => {
    await loggedIn(); const oldToken = runtime.store.getState().accessToken;
    const slow = deferred<Response>();
    businessReply = request => request.headers.get('authorization') !== `Bearer ${oldToken}` ? HttpResponse.json({ ok: true })
      : request.url.endsWith('/slow') ? slow.promise : new HttpResponse(null, { status: 401 });
    const late = runtime.aiRequest({ url: '/api/test/slow' });
    await waitFor(() => expect(count('/slow')).toBe(1));
    await runtime.request({ url: '/api/test/fast' });
    slow.resolve(new HttpResponse(null, { status: 401 })); await late;
    expect(count('/reissue')).toBe(1); expect(count('/slow')).toBe(2);
  });
  it('does not recurse when a retried request is still401', async () => {
    await loggedIn(); businessReply = () => new HttpResponse(null, { status: 401 });
    await expect(runtime.request({ url: '/api/test/main' })).rejects.toMatchObject({ code: 401 });
    expect(count('/reissue')).toBe(1); expect(count('/main')).toBe(2);
    expect(runtime.store.getState().status).toBe('anonymous');
  });
  it.each([401, 503])('shares the same failed refresh result %s without retrying originals', async status => {
    await loggedIn(); businessReply = () => new HttpResponse(null, { status: 401 });
    const gate = deferred<Response>(); refreshReply = () => gate.promise;
    const jobs = [runtime.request({ url: '/api/test/main' }), runtime.aiRequest({ url: '/api/test/ai' })];
    const results = Promise.allSettled(jobs);
    await waitFor(() => expect(count('/api/test/')).toBe(2));
    await waitFor(() => expect(count('/reissue')).toBe(1));
    gate.resolve(new HttpResponse(null, { status }));
    const settled = await results;
    expect(settled.every(row => row.status === 'rejected')).toBe(true);
    if (settled[0].status === 'rejected' && settled[1].status === 'rejected') expect(settled[0].reason === settled[1].reason).toBe(true);
    expect(count('/reissue')).toBe(1); expect(count('/api/test/')).toBe(2);
  });
  it.each(['network', '403'] as const)('does not refresh %s failures', async kind => {
    await loggedIn(); businessReply = () => kind === 'network' ? HttpResponse.error() : new HttpResponse(null, { status: 403 });
    await expect(runtime.request({ url: '/api/test/main' })).rejects.toBeDefined();
    expect(count('/reissue')).toBe(0); expect(runtime.store.getState().status).toBe('authenticated');
  });
  it('blocks token transmission outside the configured origin', async () => {
    await loggedIn(); await expect(runtime.aiRequest({ url: 'https://external.invalid/api' })).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(count('/reissue')).toBe(0);
  });
  it('observes an actual Axios ERR_NETWORK/status0 without exchanging RT', async () => {
    await loggedIn(); businessReply = () => HttpResponse.error();
    const error = await runtime.axiosInstance.get('/api/test/network').catch((value: unknown) => value);
    expect(error).toMatchObject({ code: 'ERR_NETWORK', request: { status: 0 } });
    expect(count('/reissue')).toBe(0);
  });
  it('rejects an increased absolute session expiry after refresh', async () => {
    await loggedIn(); expiry = new Date(Date.parse(expiry) + 1000).toISOString();
    await expect(runtime.refreshDemo(runtime.store.getState().generation)).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable');
    expect(runtime.store.getState().accessToken).toBeNull();
  });
});

describe('visit, cache and logout races', () => {
  it.each([200, 401])('discards old visit response %s after a new login', async status => {
    await loggedIn(); const gate = deferred<Response>(); businessReply = () => gate.promise;
    const pending = runtime.request({ url: '/api/test/late' }); const result = Promise.allSettled([pending]);
    await waitFor(() => expect(count('/late')).toBe(1));
    await runtime.logoutDemo(); await loggedIn(); const current = runtime.store.getState().accessToken;
    gate.resolve(HttpResponse.json({ private: 'old visitor' }, { status }));
    expect((await result)[0].status).toBe('rejected');
    expect(runtime.store.getState().accessToken === current).toBe(true);
    expect(runtime.store.getState().status).toBe('authenticated'); expect(count('/reissue')).toBe(0);
  });
  it('detaches the old QueryClient so late mutation rollback cannot refill the next visitor cache', async () => {
    await loggedIn(); const old = runtime.getDemoQueryClient(); old.setQueryData(['private'], { old: true });
    const gate = deferred<void>();
    const mutation = old.getMutationCache().build(old, { mutationFn: () => gate.promise.then(() => { throw new Error('Synthetic failure'); }),
      onError: () => { old.setQueryData(['private'], { old: true }); } });
    const result = mutation.execute(undefined).catch(() => {});
    await runtime.logoutDemo();
    expect(old.getQueryData(['private'])).toBeUndefined();
    await loggedIn(); const current = runtime.getDemoQueryClient();
    gate.resolve(); await result;
    expect(current === old).toBe(false); expect(current.getQueryData(['private'])).toBeUndefined();
  });
  it('logout waits for an in-flight refresh and prevents token resurrection', async () => {
    await loggedIn(); const gate = deferred<Response>(); refreshReply = () => gate.promise;
    const refreshing = runtime.refreshDemo(runtime.store.getState().generation).catch(() => {});
    await waitFor(() => expect(count('/reissue')).toBe(1));
    const ending = runtime.logoutDemo();
    expect(runtime.store.getState().accessToken).toBeNull();
    gate.resolve(okToken()); await Promise.all([refreshing, ending]);
    expect(runtime.store.getState().status).toBe('anonymous'); expect(runtime.store.getState().accessToken).toBeNull();
    expect(count('/logout')).toBe(1);
  });
  it('logout401 performs reissue/session/logout at most once', async () => {
    await loggedIn(); logoutReply = () => count('/logout') === 1 ? new HttpResponse(null, { status: 401 }) : new HttpResponse(null, { status: 204 });
    await runtime.logoutDemo();
    expect([count('/logout'), count('/reissue'), count('/session')]).toEqual([2, 1, 2]);
    expect(runtime.store.getState().status).toBe('anonymous');
  });
  it('logout401 followed by expired RT ends without login', async () => {
    await loggedIn(); logoutReply = () => new HttpResponse(null, { status: 401 }); refreshReply = () => new HttpResponse(null, { status: 401 });
    await runtime.logoutDemo(); expect(runtime.store.getState().status).toBe('anonymous');
    expect(count('/logout')).toBe(1); expect(count('/login')).toBe(1);
  });
  it('a second logout401 stops after one refresh rather than looping', async () => {
    await loggedIn(); logoutReply = () => new HttpResponse(null, { status: 401 });
    await runtime.logoutDemo();
    expect([count('/logout'), count('/reissue')]).toEqual([2, 1]);
    expect(runtime.store.getState().status).toBe('anonymous');
  });
  it('blocks late business success after logout without starting a new visit', async () => {
    await loggedIn(); const gate = deferred<Response>(); businessReply = () => gate.promise;
    const pending = runtime.aiRequest({ url: '/api/test/late-logout' });
    const result = Promise.allSettled([pending]);
    await waitFor(() => expect(count('/late-logout')).toBe(1));
    await runtime.logoutDemo(); gate.resolve(HttpResponse.json({ previousVisitor: true }));
    expect((await result)[0].status).toBe('rejected');
    expect(runtime.store.getState().status).toBe('anonymous'); expect(runtime.store.getState().accessToken).toBeNull();
  });
  it.each(['503', 'network'] as const)('logout %s stays unavailable until explicit retry', async kind => {
    await loggedIn(); logoutReply = () => kind === '503' ? new HttpResponse(null, { status: 503 }) : HttpResponse.error();
    await expect(runtime.logoutDemo()).rejects.toBeInstanceOf(runtime.DemoAuthError);
    expect(runtime.store.getState().status).toBe('unavailable'); expect(runtime.store.getState().recovery).toBe('logout');
    expect(runtime.store.getState().accessToken).toBeNull();
    logoutReply = () => new HttpResponse(null, { status: 204 }); await runtime.logoutDemo();
    expect(runtime.store.getState().status).toBe('anonymous'); expect(count('/login')).toBe(1);
  });
});

describe('real App, provider, route guard and protected queries', () => {
  it('StrictMode restores once; pending restore renders no Chart and sends zero protected HTTP', async () => {
    const gate = deferred<Response>(); refreshReply = () => gate.promise;
    await renderApp();
    await waitFor(() => expect(count('/reissue')).toBe(1));
    expect(count('/users')).toBe(0); expect(count('/transactions')).toBe(0); expect(count('/login')).toBe(0);
    expect(screen.queryByRole('navigation', { name: '체험 메뉴' })).not.toBeInTheDocument();
    gate.resolve(okToken());
    await screen.findByRole('navigation', { name: '체험 메뉴' });
    await waitFor(() => expect(count('/transactions')).toBe(1));
    fireEvent.click(screen.getByRole('button', { name: '내 소비 1월 선택' }));
    await waitFor(() => expect(count('/transactions')).toBe(3));
    expect(count('/transactions/peer')).toBe(0);
    expect(count('/reissue')).toBe(1); expect(count('/users')).toBe(1);
  });
  it('landing explicit login confirms session and navigates to API-month Pot; duplicate click issues one login', async () => {
    refreshReply = () => new HttpResponse(null, { status: 401 });
    await renderApp('/');
    const button = await screen.findByRole('button', { name: '샘플 데이터로 체험하기' });
    const gate = deferred<Response>(); loginReply = () => gate.promise;
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(count('/login')).toBe(1));
    expect(count('/users')).toBe(0); gate.resolve(okToken());
    await screen.findByRole('navigation', { name: '체험 메뉴' });
    expect(count('/session')).toBe(1); expect(count('/login')).toBe(1);
  });
  it('restoration503 shows recoverable error without login or protected queries', async () => {
    refreshReply = () => new HttpResponse(null, { status: 503 }); await renderApp();
    await screen.findByRole('button', { name: '연결 다시 시도' });
    expect(count('/login')).toBe(0); expect(count('/users')).toBe(0); expect(count('/transactions')).toBe(0);
  });
  it('restoration network failure remains unavailable and does not redirect or create a visitor', async () => {
    refreshReply = () => HttpResponse.error(); await renderApp();
    await screen.findByRole('button', { name: '연결 다시 시도' });
    expect(runtime.store.getState().status).toBe('unavailable');
    expect(count('/login') + count('/users') + count('/transactions')).toBe(0);
  });
  it('a failed user query shows retry UI without rendering Chart or changing authentication', async () => {
    businessReply = () => new HttpResponse(null, { status: 503 }); await renderApp();
    await screen.findByRole('button', { name: '다시 시도' });
    expect(runtime.store.getState().status).toBe('authenticated');
    expect(count('/transactions')).toBe(0); expect(count('/login')).toBe(0);
  });
  it('the actual Header logout failure exposes an explicit finish-logout action', async () => {
    await renderApp();
    const button = await screen.findByRole('button', { name: '체험 종료' });
    logoutReply = () => new HttpResponse(null, { status: 503 });
    fireEvent.click(button);
    const retry = await screen.findByRole('button', { name: '종료 다시 시도' });
    expect(screen.queryByRole('navigation', { name: '체험 메뉴' })).not.toBeInTheDocument();
    expect(runtime.store.getState().accessToken).toBeNull();
    logoutReply = () => new HttpResponse(null, { status: 204 });
    fireEvent.click(retry);
    await screen.findByRole('button', { name: '샘플 데이터로 체험하기' });
    expect(count('/logout')).toBe(2); expect(count('/login')).toBe(0);
  });
  it('the provider absolute-deadline timer expires without a focus event or automatic login', async () => {
    await runtime.bootstrapDemo();
    const { default: Provider } = await import('../../src/auth/AuthProvider');
    vi.useFakeTimers();
    render(<Provider><p>timer probe</p></Provider>);
    await act(async () => { await vi.advanceTimersByTimeAsync(Date.parse(expiry) - Date.now() + 1); });
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(runtime.store.getState().accessToken).toBeNull(); expect(count('/login')).toBe(0);
  });
  it('absolute expiry unmounts protected UI, clears memory/cache and never auto logs in', async () => {
    expiry = new Date(Date.now() + 30000).toISOString(); await renderApp();
    await screen.findByRole('navigation', { name: '체험 메뉴' });
    const old = runtime.getDemoQueryClient(); old.setQueryData(['private'], { value: true });
    vi.useFakeTimers(); vi.setSystemTime(new Date(Date.parse(expiry) + 1));
    act(() => { window.dispatchEvent(new Event('focus')); });
    expect(runtime.store.getState().status).toBe('anonymous'); expect(runtime.store.getState().accessToken).toBeNull();
    expect(old.getQueryData(['private'])).toBeUndefined(); expect(count('/login')).toBe(0);
    expect(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })).toBeInTheDocument();
  });
  const restoredRoutes = ['/userInfo', '/mypage', '/pot/1', '/toadAdvice'];
  it.each(restoredRoutes)('anonymous demo still blocks restored route %s', async path => {
    refreshReply = () => new HttpResponse(null, { status: 401 }); await renderApp(path);
    await screen.findByRole('button', { name: '샘플 데이터로 체험하기' });
    expect(runtime.store.getState().status).toBe('anonymous');
    expect(document.querySelector('[data-demo-page]')).toBeNull();
    expect(count('/users') + count('/transactions') + count('/budgets') + count('/cards') + count('/ai/')).toBe(0);
  });
  it.each(restoredRoutes)('restoring demo blocks restored route %s and all protected requests', async path => {
    const gate = deferred<Response>(); refreshReply = () => gate.promise;
    await renderApp(path);
    await waitFor(() => expect(count('/reissue')).toBe(1));
    expect(document.querySelector('[data-demo-page]')).toBeNull();
    expect(count('/users') + count('/transactions') + count('/budgets') + count('/cards') + count('/ai/')).toBe(0);
    gate.resolve(new HttpResponse(null, { status: 401 }));
    await screen.findByRole('button', { name: '샘플 데이터로 체험하기' });
  });
  it.each(restoredRoutes)('unavailable demo blocks restored route %s', async path => {
    refreshReply = () => new HttpResponse(null, { status: 503 }); await renderApp(path);
    await screen.findByRole('button', { name: '연결 다시 시도' });
    expect(document.querySelector('[data-demo-page]')).toBeNull();
    expect(count('/users') + count('/transactions') + count('/budgets') + count('/cards') + count('/ai/')).toBe(0);
  });
  it.each([['/userInfo', 'user-info'], ['/mypage', 'mypage'], ['/pot/1', 'pot'], ['/toadAdvice', 'advice']])(
    'authenticated demo mounts the real restored page %s', async (path, page) => {
      await renderApp(path);
      await waitFor(() => expect(document.querySelector(`[data-demo-page="${page}"]`)).not.toBeNull());
      expect(runtime.store.getState().status).toBe('authenticated');
      expect(count('/users')).toBe(1); // Existing common authentication gate only.
      expect(count('/cards') + count('/ai/') + count('/transactions/peer')).toBe(0);
      expect(requests.filter(row => row.path === '/api/users' && row.method !== 'GET')).toHaveLength(0);
      if (page === 'mypage' || page === 'user-info') expect(count('/transactions') + count('/budgets')).toBe(0);
    });
  it('demo callback does not consume an OAuth URL token or query cards', async () => {
    refreshReply = () => new HttpResponse(null, { status: 401 }); await renderApp('/auth/callback?accessToken=synthetic-ignored');
    await screen.findByRole('button', { name: '샘플 데이터로 체험하기' });
    expect(count('/cards')).toBe(0); expect(runtime.store.getState().accessToken).toBeNull();
  });
});

describe('actual readiness HTTP and initial waiting UI', () => {
  it('StrictMode shares one readiness GET and blocks protected HTTP before restoring', async () => {
    const gate = deferred<Response>(); readyReply = () => gate.promise;
    await renderApp();
    await screen.findByRole('button', { name: '데모 서버 시작 중…' });
    await waitFor(() => expect(count('/ready')).toBe(1));
    expect(count('/reissue') + count('/login') + count('/users') + count('/transactions')).toBe(0);
    expect(screen.queryByRole('navigation', { name: '체험 메뉴' })).not.toBeInTheDocument();
    gate.resolve(HttpResponse.json({ ready: true }));
    await screen.findByRole('navigation', { name: '체험 메뉴' });
    expect(count('/ready')).toBe(1); expect(count('/reissue')).toBe(1); expect(count('/login')).toBe(0);
  });
  it('shows a separate data-preparation state while the actual login POST is pending', async () => {
    refreshReply = () => new HttpResponse(null, { status: 401 }); await renderApp('/');
    const start = await screen.findByRole('button', { name: '샘플 데이터로 체험하기' });
    const gate = deferred<Response>(); loginReply = () => gate.promise;
    fireEvent.click(start);
    await screen.findByRole('button', { name: '체험 데이터 준비 중…' });
    expect(count('/users') + count('/transactions')).toBe(0);
    gate.resolve(okToken()); await screen.findByRole('navigation', { name: '체험 메뉴' });
    expect(count('/login')).toBe(1);
  });
  it.each([
    () => new HttpResponse('<html>Starting</html>', { headers: { 'Content-Type': 'text/html' } }),
    () => new HttpResponse('<html>Starting</html>', { headers: { 'Content-Type': 'application/json' } }),
    () => HttpResponse.json({ ready: false }),
    () => HttpResponse.json({ ready: true, unexpected: true }),
    () => HttpResponse.json({ ready: 'true' }),
    () => HttpResponse.json({ ready: true }, { status: 201 }),
  ])('does not treat a non-contract successful response as readiness (%#)', async reply => {
    readyReply = reply;
    const { demoHttp } = await import('../../src/api/services/demoAuth');
    expect(await demoHttp.ready(new AbortController().signal, 10_000)).toBe(false);
    expect(count('/ready')).toBe(1);
    expect(count('/login') + count('/reissue') + count('/session')).toBe(0);
  });
  it('accepts exact JSON200 and transmits GET credentials/header without a body', async () => {
    const { demoHttp } = await import('../../src/api/services/demoAuth');
    expect(await demoHttp.ready(new AbortController().signal, 10_000)).toBe(true);
    const request = requests.find(row => row.path.endsWith('/ready'))!;
    expect(request.method).toBe('GET'); expect(request.body).toBe('');
    expect(request.credentials && request.header).toBe(true);
    expect(count('/login') + count('/reissue')).toBe(0);
  });
});
