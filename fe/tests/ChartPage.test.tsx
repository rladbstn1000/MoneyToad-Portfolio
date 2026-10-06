import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { defaultScheduler, notifyManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import ChartPage from '../src/pages/ChartPage';
import { useAuthStore } from '../src/store/authStore';
import { transactionQueryKeys } from '../src/api/queryKeys';

vi.mock('recharts', () => import('./rechartsDouble'));

const ORIGIN = 'http://127.0.0.1:18080';
const YEAR = 2026;
const MONTH = 2;
const MONTH_KEY = `${YEAR}/${MONTH}`;
type User = ReturnType<typeof userEvent.setup>;
type TransactionFixture = {
  id: unknown;
  transactionDateTime: string;
  amount: number;
  merchantName: string;
  category: string;
};
type RequestRecord = { method: string; path: string; body?: unknown };
type Reply = () => Response | Promise<Response>;
type PendingReply = { promise: Promise<Response>; resolve: (response: Response) => void };

function transaction(id: unknown, category = '식비', month = MONTH, merchantName = `API 거래 ${String(id)}`): TransactionFixture {
  return { id, transactionDateTime: `${YEAR}-${String(month).padStart(2, '0')}-03T12:00:00`,
    amount: id === 2 ? 800 : 1200, merchantName, category };
}

function createScenario() {
  const months = new Map<string, TransactionFixture[]>([
    [MONTH_KEY, [transaction(1), transaction(2, '카페')]],
    [`${YEAR}/3`, [transaction(3, '식비', 3)]],
  ]);
  return {
    months,
    requests: [] as RequestRecord[],
    unexpected: [] as RequestRecord[],
    checkpoints: [] as Record<string, unknown>[],
    monthReplies: new Map<string, Reply>(),
    yearReply: (() => HttpResponse.json([{ date: `${YEAR}-02`, totalAmount: 2000, leaked: false },
      { date: `${YEAR}-03`, totalAmount: 1200, leaked: false }])) as Reply,
    peerReply: (() => HttpResponse.json([{ date: `${YEAR}-02`, totalAmount: 1800 }])) as Reply,
    patchReply: undefined as undefined | ((id: string, body: unknown) => Response | Promise<Response>),
    pending: [] as PendingReply[],
    inFlight: 0,
  };
}

let scenario = createScenario();
let queryClient: QueryClient;

async function record(request: Request, respond: (body: unknown) => Response | Promise<Response>): Promise<Response> {
  const state = scenario;
  const body: unknown = request.method === 'PATCH' ? await request.clone().json() : undefined;
  state.requests.push({ method: request.method, path: new URL(request.url).pathname,
    ...(body === undefined ? {} : { body }) });
  state.inFlight += 1;
  try {
    return await respond(body);
  } finally {
    state.inFlight -= 1;
  }
}

function applyCategory(id: string, body: unknown): TransactionFixture | undefined {
  const category = typeof body === 'object' && body !== null && 'category' in body ? body.category : undefined;
  let changed: TransactionFixture | undefined;
  for (const [key, rows] of scenario.months) {
    scenario.months.set(key, rows.map(row => {
      if (String(row.id) !== id || typeof category !== 'string') return row;
      changed = { ...row, category };
      return changed;
    }));
  }
  return changed;
}

const server = setupServer(
  http.get(`${ORIGIN}/api/transactions`, ({ request }) => record(request, () => scenario.yearReply())),
  http.get(`${ORIGIN}/api/transactions/peer`, ({ request }) => record(request, () => scenario.peerReply())),
  http.get(`${ORIGIN}/api/transactions/:year/:month/categories`, ({ request }) => record(request, () =>
    HttpResponse.json([{ category: '식비', totalAmount: 1200, leakedAmount: 0 },
      { category: '카페', totalAmount: 800, leakedAmount: 0 }]))),
  http.get(`${ORIGIN}/api/transactions/:year/:month`, ({ request, params }) => record(request, () => {
    const key = `${params.year}/${params.month}`;
    return scenario.monthReplies.get(key)?.() ?? HttpResponse.json(scenario.months.get(key) ?? []);
  })),
  http.patch(`${ORIGIN}/api/transactions/:id/category`, ({ request, params }) => record(request, body => {
    const id = String(params.id);
    return scenario.patchReply?.(id, body) ?? HttpResponse.json(applyCategory(id, body) ?? { id: Number(id) });
  })),
  // The unchanged interceptor treats transport failures as a refresh attempt.
  // This handler keeps that request synthetic and counts it separately.
  http.post(`${ORIGIN}/api/auth/reissue`, ({ request }) => record(request, () =>
    HttpResponse.json({ accessToken: 'a3-synthetic-refreshed-token' }))),
);

beforeAll(() => server.listen({
  onUnhandledRequest(request, print) {
    // MSW may throw before emitting request:unhandled, so record first.
    scenario.unexpected.push({ method: request.method, path: new URL(request.url).pathname });
    print.error();
  },
}));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-09-09T03:00:00Z'));
  scenario = createScenario();
  localStorage.clear();
  useAuthStore.setState({ accessToken: 'a3-synthetic-access-token' });
  vi.spyOn(window, 'alert').mockImplementation(() => {});
  queryClient = new QueryClient({ defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: false, gcTime: Infinity },
    mutations: { retry: false },
  } });
});

afterEach(async () => {
  cleanup();
  for (const pending of scenario.pending) pending.resolve(HttpResponse.json([]));
  await waitFor(() => expect(scenario.inFlight).toBe(0));
  await queryClient.cancelQueries();
  queryClient.clear();
  useAuthStore.getState().clear();
  localStorage.clear();
  server.resetHandlers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  console.info('A3_EVIDENCE ' + JSON.stringify({
    test: expect.getState().currentTestName,
    layer: 'dom_request_double',
    patches: patchRequests(),
    reissueCount: reissueCount(),
    monthlyGetCount: monthlyRequests().length,
    checkpoints: scenario.checkpoints,
    unexpectedRequests: scenario.unexpected,
  }));
  expect(scenario.unexpected, 'Unregistered requests must fail even when application error handling catches them').toEqual([]);
});

afterAll(() => server.close());

function mountChart() {
  const user = userEvent.setup();
  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/chart']}>
    <ChartPage />
  </MemoryRouter></QueryClientProvider>);
  return user;
}

function deferredReply(): PendingReply {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>(done => { resolve = done; });
  const pending = { promise, resolve };
  scenario.pending.push(pending);
  return pending;
}

function patchRequests() { return scenario.requests.filter(request => request.method === 'PATCH'); }
function monthlyRequests() {
  return scenario.requests.filter(request => request.method === 'GET' && /^\/api\/transactions\/\d+\/\d+$/.test(request.path));
}
function reissueCount() { return scenario.requests.filter(request => request.path === '/api/auth/reissue').length; }

async function settle() {
  await waitFor(() => expect(queryClient.isMutating()).toBe(0));
  await waitFor(() => expect(queryClient.isFetching()).toBe(0));
  await act(async () => { await Promise.resolve(); });
}

async function chooseMonth(user: User, month = MONTH) {
  await user.click(await screen.findByRole('button', { name: `내 소비 ${month}월 선택` }));
  await waitFor(() => expect(monthlyRequests().some(request => request.path === `/api/transactions/${YEAR}/${month}`)).toBe(true));
}

async function chooseCategory(user: User, trigger: HTMLElement, category: string) {
  await user.click(trigger);
  const option = await screen.findByRole('option', { name: category });
  await user.click(option);
}

async function attemptAlternateCategory(user: User, row: HTMLElement) {
  const trigger = within(row).queryByRole('combobox');
  if (!trigger || trigger.hasAttribute('disabled')) return;
  await chooseCategory(user, trigger, trigger.textContent?.trim() === '식비' ? '카페' : '식비');
}

function transactionRows() { return screen.queryAllByRole('row').filter(row => within(row).queryByRole('combobox')); }

function displayedSeries(key: 'me' | 'peers'): unknown[] {
  const output = screen.queryByTestId('chart-line-data');
  const data = JSON.parse(output?.textContent ?? '[]') as Record<string, unknown>[];
  return data.map(point => point[key]);
}

function checkpoint(point: string) {
  scenario.checkpoints.push({ point, riskyPatchCount: patchRequests().length, reissueCount: reissueCount() });
}

async function realRow(id = 1) {
  return screen.findByRole('row', { name: new RegExp(`API 거래 ${id}(?:\\s|$)`) });
}

function expectNoDisplayedSample() {
  expect(screen.queryByText(/^(방앗간|주막|장터|포목점|기와집|전당포)$/)).not.toBeInTheDocument();
}

describe('ChartPage data boundary through actual hooks, request and JPSelect', () => {
  it('keeps the complete heading and feedback outside the pond plotting stage', async () => {
    mountChart(); await settle();
    const heading = screen.getByRole('heading', { name: '월간 소비 비교' }).closest<HTMLElement>('.jp-page-title-section');
    const stage = document.querySelector('.jp-stage');
    expect(heading?.nextElementSibling).toBe(stage);
    expect(heading).toContainElement(document.querySelector('.jp-query-status'));
    expect(stage).not.toContainElement(heading);
    expect(stage).toContainElement(screen.getByRole('img', { name: 'Water' }));
    expect(stage).toContainElement(document.querySelector('.jp-linechart-wrap'));
    expect(stage?.nextElementSibling).toBe(screen.getByRole('navigation', { name: '월별 상세 보기' }));
  });
  it('empty response never lets the generated 1-0 sample become PATCH transaction 1', async () => {
    scenario.months.set(MONTH_KEY, []);
    const user = mountChart();
    await chooseMonth(user);
    await settle();
    const sampleRow = transactionRows()[0];
    // Identical scenario before/after the fix: if the UI exposes an editable
    // fallback, exercise the real Radix selection before asserting PATCH zero.
    if (sampleRow) await attemptAlternateCategory(user, sampleRow);
    await settle();
    scenario.checkpoints.push({ point: 'sample_edit_attempt', displayedFallbackRow: Boolean(sampleRow),
      riskyPatchCount: patchRequests().length });
    expect(patchRequests()).toEqual([]);
    expect(screen.getByText('거래 내역이 없습니다.', { exact: true })).toBeInTheDocument();
    expect(screen.getByText(/총 소비 금액:/)).toHaveTextContent(/총 소비 금액:\s*0원/);
    expect(transactionRows()).toHaveLength(0);
  });

  it('distinguishes no month selection from pending annual and peer queries without issuing month zero requests', async () => {
    const year = deferredReply();
    const peer = deferredReply();
    scenario.yearReply = () => year.promise;
    scenario.peerReply = () => peer.promise;
    mountChart();
    await waitFor(() => expect(scenario.requests.filter(request =>
      ['/api/transactions', '/api/transactions/peer'].includes(request.path))).toHaveLength(2));
    expect(screen.getByText('월을 선택해 주세요.', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('연간 소비를 불러오는 중입니다.', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('또래 소비를 불러오는 중입니다.', { exact: true })).toBeInTheDocument();
    expect(displayedSeries('me').every(value => value == null)).toBe(true);
    expect(displayedSeries('peers').every(value => value == null)).toBe(true);
    expect(monthlyRequests()).toEqual([]);
    expect(scenario.requests.some(request => request.path.includes('/0/0'))).toBe(false);
    expect(transactionRows()).toHaveLength(0);
    expect(patchRequests()).toEqual([]);
  });

  it('keeps the first pending monthly query free of fallback rows and writes', async () => {
    const monthly = deferredReply();
    scenario.monthReplies.set(MONTH_KEY, () => monthly.promise);
    const user = mountChart();
    await chooseMonth(user);
    expect(screen.getByText('거래 내역을 불러오는 중입니다.', { exact: true })).toBeInTheDocument();
    expect(transactionRows()).toHaveLength(0);
    expectNoDisplayedSample();
    expect(patchRequests()).toEqual([]);
  });

  it.each(['http 500', 'transport'] as const)('recovers from monthly %s through the real retry and request path', async (failure) => {
    scenario.monthReplies.set(MONTH_KEY, () => failure === 'transport'
      ? HttpResponse.error()
      : HttpResponse.json({ message: '합성 조회 실패' }, { status: 500 }));
    const user = mountChart();
    await chooseMonth(user);
    await settle();
    expect(screen.getByText('거래 내역을 불러오지 못했습니다.', { exact: true })).toBeInTheDocument();
    expect(transactionRows()).toHaveLength(0);
    expectNoDisplayedSample();
    checkpoint('failed_query_before_retry');
    expect(patchRequests()).toEqual([]);
    expect(reissueCount()).toBe(failure === 'transport' ? 1 : 0);

    scenario.monthReplies.delete(MONTH_KEY);
    await user.click(screen.getByRole('button', { name: '거래 내역 다시 시도' }));
    const row = await realRow();
    await settle();
    expect(screen.queryByText('거래 내역을 불러오지 못했습니다.', { exact: true })).not.toBeInTheDocument();
    await chooseCategory(user, within(row).getByRole('combobox'), '카페');
    await settle();
    expect(patchRequests()).toEqual([{ method: 'PATCH', path: '/api/transactions/1/category', body: { category: '카페' } }]);
    expect(within(await realRow()).getByRole('combobox')).toHaveTextContent('카페');
  });

  it('shows successful empty annual and peer data without fabricated totals or peer ratios', async () => {
    scenario.yearReply = () => HttpResponse.json([]);
    scenario.peerReply = () => HttpResponse.json([]);
    mountChart();
    await settle();
    expect(screen.getByText('연간 소비 내역이 없습니다.', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('또래 소비 내역이 없습니다.', { exact: true })).toBeInTheDocument();
    expect(displayedSeries('me').every(value => value == null || value === 0)).toBe(true);
    expect(displayedSeries('peers').every(value => value == null || value === 0)).toBe(true);
    expect(monthlyRequests()).toEqual([]);
    expect(patchRequests()).toEqual([]);
  });

  it('keeps annual loading independent of a completed peer response', async () => {
    const annual = deferredReply();
    scenario.yearReply = () => annual.promise;
    mountChart();
    await waitFor(() => expect(queryClient.getQueryState(transactionQueryKeys.peerYear())?.status).toBe('success'));
    expect(screen.getByText('연간 소비를 불러오는 중입니다.', { exact: true })).toBeInTheDocument();
    expect(screen.queryByText('또래 소비를 불러오는 중입니다.', { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText('또래 소비를 불러오지 못했습니다.', { exact: true })).not.toBeInTheDocument();
    expect(displayedSeries('me').every(value => value == null)).toBe(true);
    expect(patchRequests()).toEqual([]);
    annual.resolve(HttpResponse.json([]));
    await settle();
    expect(screen.getByText('연간 소비 내역이 없습니다.', { exact: true })).toBeInTheDocument();
  });

  it('shows annual and peer failures as separate unknown states rather than zero or sample success', async () => {
    scenario.yearReply = () => HttpResponse.json({ message: '합성 연간 실패' }, { status: 500 });
    scenario.peerReply = () => HttpResponse.json({ message: '합성 또래 실패' }, { status: 500 });
    mountChart();
    await settle();
    expect(screen.getByText('연간 소비를 불러오지 못했습니다.', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('또래 소비를 불러오지 못했습니다.', { exact: true })).toBeInTheDocument();
    expect(displayedSeries('me').every(value => value == null)).toBe(true);
    expect(displayedSeries('peers').every(value => value == null)).toBe(true);
    expect(patchRequests()).toEqual([]);
  });

  it('allows a confirmed real monthly record to be edited while peer data is unavailable', async () => {
    scenario.peerReply = () => HttpResponse.json({ message: '합성 또래 실패' }, { status: 500 });
    const user = mountChart();
    await chooseMonth(user);
    const row = await realRow();
    await settle();
    expect(screen.getByText('또래 소비를 불러오지 못했습니다.', { exact: true })).toBeInTheDocument();
    expect(displayedSeries('peers').every(value => value == null)).toBe(true);
    expect(within(row).getByRole('combobox')).toBeEnabled();
    await chooseCategory(user, within(row).getByRole('combobox'), '카페');
    await settle();
    expect(patchRequests()).toEqual([{ method: 'PATCH', path: '/api/transactions/1/category', body: { category: '카페' } }]);
  });

  it('edits API record id 1 once with the exact body and reflects the existing query invalidation', async () => {
    const user = mountChart();
    await chooseMonth(user);
    const row = await realRow();
    await settle();
    const getsBefore = monthlyRequests().length;
    await chooseCategory(user, within(row).getByRole('combobox'), '카페');
    await settle();
    expect(patchRequests()).toEqual([{ method: 'PATCH', path: '/api/transactions/1/category', body: { category: '카페' } }]);
    expect(monthlyRequests().length).toBeGreaterThan(getsBefore);
    expect(within(await realRow()).getByRole('combobox')).toHaveTextContent('카페');
    const cached = queryClient.getQueryData<TransactionFixture[]>(transactionQueryKeys.monthly(YEAR, MONTH));
    expect(cached?.find(row => row.id === 1)?.category).toBe('카페');
  });

  it.each([
    ['hyphenated sample key', '1-0'], ['partially numeric text', '1abc'], ['empty string', ''],
    ['zero', 0], ['negative', -1], ['fraction', 1.5], ['unsafe integer', Number.MAX_SAFE_INTEGER + 1], ['null', null],
  ])('does not repair an invalid API identifier: %s', async (label, id) => {
    scenario.months.set(MONTH_KEY, [transaction(id, '식비', MONTH, '잘못된 API ID 거래')]);
    const user = mountChart();
    await chooseMonth(user);
    await settle();
    const row = screen.queryByRole('row', { name: /잘못된 API ID 거래/ });
    if (row) await attemptAlternateCategory(user, row);
    await settle();
    scenario.checkpoints.push({ point: 'invalid_api_identifier', label, riskyPatchCount: patchRequests().length });
    expect(patchRequests()).toEqual([]);
    if (row) {
      const trigger = within(row).queryByRole('combobox');
      if (trigger) expect(trigger).toBeDisabled();
    }
  });

  it('does not edit previous or cached month rows while the newly selected month is fetching', async () => {
    const march = deferredReply();
    queryClient.setQueryData(transactionQueryKeys.monthly(YEAR, 3), [transaction(33, '식비', 3, '이전 캐시 거래')]);
    scenario.monthReplies.set(`${YEAR}/3`, () => march.promise);
    const user = mountChart();
    await chooseMonth(user);
    await realRow();
    await settle();
    await chooseMonth(user, 3);
    expect(screen.getByText('거래 내역을 불러오는 중입니다.', { exact: true })).toBeInTheDocument();
    const cachedRow = screen.getByRole('row', { name: /이전 캐시 거래/ });
    expect(within(cachedRow).getByRole('combobox')).toBeDisabled();
    for (const row of transactionRows()) {
      await attemptAlternateCategory(user, row);
      expect(within(row).getByRole('combobox')).toBeDisabled();
    }
    checkpoint('changed_month_cached_rows');
    expect(patchRequests()).toEqual([]);
    scenario.monthReplies.delete(`${YEAR}/3`);
    march.resolve(HttpResponse.json(scenario.months.get(`${YEAR}/3`)));
    const row = await realRow(3);
    await settle();
    await chooseCategory(user, within(row).getByRole('combobox'), '카페');
    await settle();
    expect(patchRequests()).toEqual([{ method: 'PATCH', path: '/api/transactions/3/category', body: { category: '카페' } }]);
  });

  it('disables current cached rows during a background refetch and enables confirmed data afterwards', async () => {
    const user = mountChart();
    await chooseMonth(user);
    await realRow();
    await settle();
    const refresh = deferredReply();
    scenario.monthReplies.set(MONTH_KEY, () => refresh.promise);
    const before = monthlyRequests().length;
    act(() => { void queryClient.invalidateQueries({ queryKey: transactionQueryKeys.monthly(YEAR, MONTH) }); });
    await waitFor(() => expect(monthlyRequests().length).toBeGreaterThan(before));
    expect(screen.getByText('거래 내역을 불러오는 중입니다.', { exact: true })).toBeInTheDocument();
    const cachedRow = await realRow();
    expect(within(cachedRow).getByRole('combobox')).toBeDisabled();
    for (const row of transactionRows()) {
      await attemptAlternateCategory(user, row);
      expect(within(row).getByRole('combobox')).toBeDisabled();
    }
    checkpoint('background_refetch');
    expect(patchRequests()).toEqual([]);
    scenario.monthReplies.delete(MONTH_KEY);
    refresh.resolve(HttpResponse.json(scenario.months.get(MONTH_KEY)));
    await settle();
    const row = await realRow();
    expect(within(row).getByRole('combobox')).toBeEnabled();
    await chooseCategory(user, within(row).getByRole('combobox'), '카페');
    await settle();
    expect(patchRequests()).toHaveLength(1);
  });

  it('keeps the server value while saving and blocks repeated category operations until completion', async () => {
    const save = deferredReply();
    scenario.patchReply = () => save.promise;
    const user = mountChart();
    await chooseMonth(user);
    const row = await realRow();
    await settle();
    await chooseCategory(user, within(row).getByRole('combobox'), '카페');
    await waitFor(() => expect(patchRequests()).toHaveLength(1));
    const trigger = within(await realRow()).getByRole('combobox');
    expect(trigger).toHaveTextContent('식비');
    expect(trigger).toBeDisabled();
    for (const row of transactionRows()) {
      const select = within(row).getByRole('combobox');
      expect(select).toBeDisabled();
      await user.click(select);
    }
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
    expect(patchRequests()).toHaveLength(1);
    const changed = applyCategory('1', { category: '카페' });
    scenario.patchReply = undefined;
    save.resolve(HttpResponse.json(changed ?? {}));
    await settle();
    expect(within(await realRow()).getByRole('combobox')).toHaveTextContent('카페');
    expect(within(await realRow()).getByRole('combobox')).toBeEnabled();
    expect(patchRequests()).toHaveLength(1);
  });

  it('does not PATCH when the existing category is selected again', async () => {
    const user = mountChart();
    await chooseMonth(user);
    const row = await realRow();
    await settle();
    await chooseCategory(user, within(row).getByRole('combobox'), '식비');
    await settle();
    expect(patchRequests()).toEqual([]);
    expect(within(await realRow()).getByRole('combobox')).toHaveTextContent('식비');
  });

  it('rejects an ID removed from the current query cache even before the old UI receives that notification', async () => {
    const user = mountChart();
    await chooseMonth(user);
    const row = await realRow();
    await settle();
    await user.click(within(row).getByRole('combobox'));
    const option = await screen.findByRole('option', { name: '카페' });
    const notifications: Array<() => void> = [];
    // Use Query's public scheduler to expose the real stale-UI window. The
    // cache, query observer, JPSelect, handler and HTTP functions stay real.
    notifyManager.setScheduler(callback => { notifications.push(callback); });
    try {
      act(() => {
        queryClient.setQueryData<TransactionFixture[]>(transactionQueryKeys.monthly(YEAR, MONTH),
          rows => rows?.filter(transaction => transaction.id !== 1));
      });
      const current = queryClient.getQueryData<TransactionFixture[]>(transactionQueryKeys.monthly(YEAR, MONTH));
      expect(current?.some(transaction => transaction.id === 1)).toBe(false);
      expect(row).toBeInTheDocument();
      expect(within(row).getByRole('combobox', { hidden: true })).toBeEnabled();
      await user.click(option);
      await settle();
      scenario.checkpoints.push({ point: 'current_list_member_removed_before_ui_notification',
        idInCurrentCache: false, oldUiWasPresent: true, riskyPatchCount: patchRequests().length });
      expect(patchRequests()).toEqual([]);
    } finally {
      notifyManager.setScheduler(defaultScheduler);
      await act(async () => { for (const notify of notifications.splice(0)) notify(); });
    }
    await waitFor(() => expect(screen.queryByRole('row', { name: /API 거래 1(?:\s|$)/ })).not.toBeInTheDocument());
  });

  it('keeps the original value and reports a failed save before allowing a later successful retry', async () => {
    scenario.patchReply = () => HttpResponse.json({ message: '합성 권한 거절' }, { status: 403 });
    const user = mountChart();
    await chooseMonth(user);
    const row = await realRow();
    await settle();
    await chooseCategory(user, within(row).getByRole('combobox'), '카페');
    await settle();
    expect(patchRequests()).toEqual([{ method: 'PATCH', path: '/api/transactions/1/category', body: { category: '카페' } }]);
    expect(screen.getByText('카테고리를 변경하지 못했습니다.', { exact: true })).toBeInTheDocument();
    expect(within(await realRow()).getByRole('combobox')).toHaveTextContent('식비');
    expect(within(await realRow()).getByRole('combobox')).toBeEnabled();
    expect(reissueCount()).toBe(0);
    scenario.patchReply = undefined;
    await chooseCategory(user, within(await realRow()).getByRole('combobox'), '카페');
    await settle();
    expect(patchRequests()).toHaveLength(2);
    expect(within(await realRow()).getByRole('combobox')).toHaveTextContent('카페');
    expect(screen.queryByText('카테고리를 변경하지 못했습니다.', { exact: true })).not.toBeInTheDocument();
  });

  it('preserves the existing category filter without turning filter choices into PATCH requests', async () => {
    const user = mountChart();
    await chooseMonth(user);
    await realRow();
    await settle();
    const filter = screen.getAllByRole('combobox').find(trigger => !trigger.closest('tr'));
    expect(filter).toBeDefined();
    await chooseCategory(user, filter!, '카페');
    expect(screen.queryByRole('row', { name: /API 거래 1(?:\s|$)/ })).not.toBeInTheDocument();
    expect(await realRow(2)).toBeInTheDocument();
    expect(transactionRows()).toHaveLength(1);
    await chooseCategory(user, filter!, '전체');
    expect(await realRow()).toBeInTheDocument();
    expect(transactionRows()).toHaveLength(2);
    expect(patchRequests()).toEqual([]);
  });
});
