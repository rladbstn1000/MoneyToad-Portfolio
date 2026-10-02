import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import LeakPotPage from '../../src/pages/LeakPotPage';
import { adaptDemoBudgets, DEMO_BUDGET_CATEGORIES, parseDemoBudgetMonth } from '../../src/demo/demoBudgetPresentation';
import { monthlyBudgetQueryKeys, transactionQueryKeys } from '../../src/api/queryKeys';
import { useAuthStore } from '../../src/store/authStore';
import type { MonthlyBudgetResponse } from '../../src/types';

vi.mock('lottie-react', () => ({ default: () => <span data-testid="demo-water" /> }));
const origin = 'http://127.0.0.1:18080';
const seed: Record<string, [number, number]> = {
  '주거 / 통신': [600000, 500000], '교통 / 차량': [80000, 60000], '식비': [200000, 180000],
  '카페': [40000, 58000], '마트 / 편의점': [150000, 90000], '문화생활': [60000, 20000],
};
const fixture = (): MonthlyBudgetResponse[] => DEMO_BUDGET_CATEGORIES.map((category, index) => ({
  id: seed[category] ? index + 1 : null, category, budget: seed[category]?.[0] ?? 0,
  spending: seed[category]?.[1] ?? 0, initialBudget: 0,
}));
let rows: MonthlyBudgetResponse[];
let client: QueryClient;
let requests: string[];
let patches: { budgetId: number; budget: number }[];
let failPatch: boolean;
let failedIds: Set<number>;
let holds: Map<number, Promise<void>>;
let monthlyReply: () => Response;
let release: (() => void) | undefined;
let hold: Promise<void> | undefined;
function annual() {
  return Array.from({ length: 12 }, (_, index) => ({ date: `2024-${String(index + 1).padStart(2, '0')}`,
    totalAmount: index === 11 ? 908000 : 802000,
    leaked: index === 11 && rows.some(row => row.spending > row.budget) }));
}
const server = setupServer(
  http.get(`${origin}/leakPot/water.json`, () => HttpResponse.json({})),
  http.get(`${origin}/api/transactions`, ({ request }) => {
    requests.push(new URL(request.url).pathname); return HttpResponse.json(annual());
  }),
  http.get(`${origin}/api/budgets/:year/:month`, ({ request }) => {
    requests.push(new URL(request.url).pathname); return monthlyReply();
  }),
  http.patch(`${origin}/api/budgets`, async ({ request }) => {
    const body: unknown = await request.json();
    if (!body || typeof body !== 'object' || !('budgetId' in body) || !('budget' in body)
      || typeof body.budgetId !== 'number' || typeof body.budget !== 'number') return new HttpResponse(null, { status: 400 });
    const { budgetId, budget } = body;
    patches.push({ budgetId, budget });
    await (holds.get(budgetId) ?? hold);
    if (failPatch || failedIds.has(budgetId)) return new HttpResponse(null, { status: 503 });
    rows = rows.map(row => row.id === budgetId ? { ...row, budget } : row);
    return HttpResponse.json({ budgetId, budget });
  }),
);
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterAll(() => server.close());
beforeEach(() => {
  rows = fixture(); requests = []; patches = []; failPatch = false; failedIds = new Set(); holds = new Map(); hold = undefined; release = undefined;
  monthlyReply = () => HttpResponse.json(rows);
  useAuthStore.setState({ status: 'authenticated', accessToken: 'synthetic-pot-access', operation: null,
    generation: 50, revision: 1, expiresAt: Date.now() + 3600000 });
  client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false }, mutations: { retry: false } } });
  const fetch = globalThis.fetch;
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => fetch(typeof input === 'string' ? new URL(input, origin) : input, init));
});
afterEach(async () => {
  cleanup(); release?.(); await client.cancelQueries(); client.clear();
  useAuthStore.getState().clear(); vi.unstubAllGlobals(); server.resetHandlers();
});
function mount(path = '/pot/12') {
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <Routes><Route path="/pot" element={<LeakPotPage />} /><Route path="/pot/:month" element={<LeakPotPage />} /></Routes>
  </MemoryRouter></QueryClientProvider>);
}
const cafe = () => screen.findByRole('slider', { name: '카페 한도' });

describe('demo budget presentation boundary', () => {
  it('keeps six persisted and six absent IDs without fabricating writable records', () => {
    const result = adaptDemoBudgets(fixture());
    expect(result).toHaveLength(12); expect(result?.filter(row => row.id !== null)).toHaveLength(6);
    expect(result?.filter(row => row.id === null)).toHaveLength(6);
  });
  it.each([0, -1, 0.5, Number.NaN, '2', undefined])('rejects malformed persisted ID (%s)', id => {
    expect(adaptDemoBudgets(fixture().map((row, index) => index === 0 ? { ...row, id } : row))).toBeNull();
  });
  it('rejects duplicate IDs and categories', () => {
    const values = fixture(); values[1].id = values[0].id; expect(adaptDemoBudgets(values)).toBeNull();
    const other = fixture(); other[1].category = other[0].category; expect(adaptDemoBudgets(other)).toBeNull();
  });
  it.each(['0', '13', '1x', '01', '-1', undefined])('rejects a non-exact route month (%s)', value => {
    expect(parseDemoBudgetMonth(value)).toBeNull();
  });
});

describe('real demo budget query and mutation flow', () => {
  it('uses the stored API anchor, shows twelve rows, six readonly, crack and water', async () => {
    const view = mount('/pot'); await cafe();
    expect(requests).toContain('/api/budgets/2024/12');
    expect(requests).not.toContain('/api/budgets');
    expect(screen.getAllByRole('slider')).toHaveLength(12);
    expect(screen.getAllByRole('slider').filter(row => (row as HTMLInputElement).disabled)).toHaveLength(6);
    expect(screen.getByText('총 18,000냥이 새고 있소!')).toBeVisible();
    expect(view.container.querySelectorAll('.crack')).toHaveLength(1);
    expect(await screen.findByTestId('demo-water')).toBeInTheDocument();
    expect(screen.getAllByText('기준 예산 없음 · 체험에서 조정할 수 없음')).toHaveLength(6);
  });
  it('keeps month navigation before a distinct pot stage and budget panel in document order', async () => {
    const view = mount(); await cafe();
    const navigation = view.container.querySelector('.month-navigation');
    const stage = view.container.querySelector('.demo-pot-stage');
    const panel = view.container.querySelector('.demo-pot-page .control-panel');
    expect(navigation).not.toBeNull(); expect(stage).not.toBeNull(); expect(panel).not.toBeNull();
    expect(navigation!.compareDocumentPosition(stage!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(stage!.nextElementSibling).toBe(panel);
    expect(stage!.querySelector('.pot-container')).not.toBeNull();
    expect(stage!.querySelector('.characters')).not.toBeNull();
    expect(stage!.querySelector('#pot-body image')).not.toBeNull();
  });
  it('updates the pot immediately then persists the last amount once and invalidates Chart data', async () => {
    const view = mount(); const input = await cafe();
    client.setQueryData(transactionQueryKeys.categories(2024, 12), [{ leakedAmount: 18000 }]);
    fireEvent.change(input, { target: { value: '50000' } });
    fireEvent.change(input, { target: { value: '60000' } });
    expect(patches).toHaveLength(0);
    expect(screen.getByText('완벽하오! 새는 돈이 없소!')).toBeVisible();
    expect(view.container.querySelectorAll('.crack')).toHaveLength(0);
    await waitFor(() => expect(patches).toEqual([{ budgetId: 2, budget: 60000 }]));
    await waitFor(() => expect(input).not.toBeDisabled());
    expect(client.getQueryState(transactionQueryKeys.categories(2024, 12))?.isInvalidated).toBe(true);
    expect(rows.find(row => row.category === '카페')?.budget).toBe(60000);
    fireEvent.change(input, { target: { value: '40000' } });
    await waitFor(() => expect(patches).toHaveLength(2));
    await waitFor(() => expect(screen.getByText('총 18,000냥이 새고 있소!')).toBeVisible());
  });
  it('does not patch a read-only row even if a change event is dispatched', async () => {
    mount(); await cafe();
    const input = screen.getByRole('slider', { name: '교육 한도' });
    expect(input).toHaveAttribute('aria-valuetext', '기준 예산 없음');
    const legend = input.closest('.demo-budget-row')?.querySelector('.slider-legend');
    expect(legend).toHaveTextContent('기준 예산 없음');
    expect(legend).not.toHaveTextContent(/한도\s*0냥/);
    fireEvent.change(input, { target: { value: '10000' } });
    await new Promise(resolve => setTimeout(resolve, 550)); expect(patches).toHaveLength(0);
  });
  it('removes failed pending values, refetches and reports the failure without POST retry', async () => {
    failPatch = true; mount(); const input = await cafe();
    fireEvent.change(input, { target: { value: '60000' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('한도를 저장하지 못했습니다');
    expect(input).toHaveValue('40000'); expect(patches).toHaveLength(1);
    expect(requests.filter(path => path === '/api/budgets/2024/12')).toHaveLength(2);
  });
  it.each(['month', 'unmount', 'logout', 'generation'] as const)('cancels pending saves on %s', async reason => {
    const view = mount(); const input = await cafe(); fireEvent.change(input, { target: { value: '60000' } });
    if (reason === 'month') fireEvent.click(screen.getByText('11월').closest('button')!);
    if (reason === 'unmount') view.unmount();
    if (reason === 'logout') act(() => useAuthStore.setState({ operation: 'logout' }));
    if (reason === 'generation') act(() => useAuthStore.setState({ generation: 51 }));
    await new Promise(resolve => setTimeout(resolve, 550)); expect(patches).toHaveLength(0);
  });
  it('rejects a late completion from a previous generation without reviving pending values', async () => {
    hold = new Promise<void>(resolve => { release = resolve; });
    mount(); const input = await cafe(); fireEvent.change(input, { target: { value: '60000' } });
    await waitFor(() => expect(patches).toHaveLength(1));
    act(() => useAuthStore.setState({ generation: 51 }));
    await waitFor(() => expect(screen.getByRole('slider', { name: '카페 한도' })).toHaveValue('40000'));
    await act(async () => { release?.(); await new Promise(resolve => setTimeout(resolve, 30)); });
    expect(screen.getByRole('slider', { name: '카페 한도' })).toHaveValue('40000');
    expect(screen.queryByText('한도를 저장하고 있습니다.')).not.toBeInTheDocument();
  });
  it('keeps another row success when an overlapping save fails', async () => {
    failedIds.add(2);
    holds.set(2, new Promise<void>(resolve => { release = resolve; }));
    mount(); const input = await cafe();
    fireEvent.change(input, { target: { value: '60000' } });
    fireEvent.change(screen.getByRole('slider', { name: '식비 한도' }), { target: { value: '210000' } });
    await waitFor(() => expect(patches).toHaveLength(2));
    await waitFor(() => expect(rows.find(row => row.category === '식비')?.budget).toBe(210000));
    await act(async () => { release?.(); });
    await screen.findByRole('alert');
    expect(screen.getByRole('slider', { name: '식비 한도' })).toHaveValue('210000');
    expect(screen.getByRole('slider', { name: '카페 한도' })).toHaveValue('40000');
  });
  it('allows at most one in-flight save for a row', async () => {
    hold = new Promise<void>(resolve => { release = resolve; });
    mount(); const input = await cafe();
    fireEvent.change(input, { target: { value: '60000' } });
    await waitFor(() => expect(patches).toHaveLength(1));
    expect(input).toBeDisabled();
    fireEvent.change(input, { target: { value: '65000' } });
    await new Promise(resolve => setTimeout(resolve, 550)); expect(patches).toHaveLength(1);
    await act(async () => { release?.(); });
  });
  it('checks the current fetched row membership again when debounce expires', async () => {
    mount(); const input = await cafe();
    fireEvent.change(input, { target: { value: '60000' } });
    act(() => client.setQueryData(monthlyBudgetQueryKeys.monthly(2024, 12), rows.map(row => row.id === 2 ? { ...row, id: null } : row)));
    await new Promise(resolve => setTimeout(resolve, 550)); expect(patches).toHaveLength(0);
  });
  it.each(['empty', 'error', 'invalid'] as const)('has an explicit %s state with no sample slider or save', async mode => {
    monthlyReply = () => mode === 'error' ? new HttpResponse(null, { status: 503 })
      : HttpResponse.json(mode === 'empty' ? [] : [{ id: 1 }]);
    mount();
    await screen.findByText(mode === 'error' ? '월별 소비를 불러오지 못했습니다.'
      : mode === 'empty' ? '이 달의 기준 예산이 없습니다.' : '월별 소비 데이터 형식을 확인할 수 없습니다.');
    expect(screen.queryAllByRole('slider')).toHaveLength(0); expect(patches).toHaveLength(0);
  });
});
