import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import LeakPotPage from '../src/pages/LeakPotPage';
import ToadAdvice from '../src/pages/ToadAdvice';
import { useAuthStore } from '../src/store/authStore';
import { monthlyBudgetQueryKeys } from '../src/api/queryKeys';
import type { MonthlyBudgetResponse } from '../src/types';

const lottie = vi.hoisted(() => ({ values: Array<unknown>() }));
vi.mock('lottie-react', () => ({ default: ({ animationData }: { animationData: unknown }) => {
  lottie.values.push(animationData); return <output data-testid="lottie-data">animation</output>;
} }));
const ORIGIN = 'http://127.0.0.1:18080';
const animation = { v: '5.0', fr: 30, ip: 0, op: 1, layers: [] };
let client: QueryClient;
let unexpected: string[];
let images: string[];
let patches: { budgetId: number; budget: number }[];
let rows: MonthlyBudgetResponse[];
let failPatch: boolean;
let animationReply: typeof animation | null;
let sheets: ReturnType<typeof sheet>[];
let monthQueries: string[];
let holds: Map<number, Promise<void>>;
let releases: (() => void)[];
function hold(id: number) {
  let release!: () => void;
  holds.set(id, new Promise<void>(resolve => { release = resolve; }));
  releases.push(release); return release;
}
function prediction(real: number | null, result: boolean | null, current = 100) {
  return { min: 80, max: 120, avg: 100, current, real, result };
}
function sheet(month: number, predictions: Record<string, ReturnType<typeof prediction>>, year = 2026) {
  return { month, year, categories_count: Object.keys(predictions).length, categories_prediction: predictions,
    categories_detail: {} };
}
const server = setupServer(
  http.get(`${ORIGIN}/leakPot/water.json`, () => HttpResponse.json(animationReply)),
  http.get(`${ORIGIN}/api/budgets`, () => HttpResponse.json([{ budgetDate: '2026-09-01', leaked: true }])),
  http.get(`${ORIGIN}/api/budgets/:year/:month`, () => HttpResponse.json(rows)),
  http.patch(`${ORIGIN}/api/budgets`, async ({ request }) => {
    const body: unknown = await request.json();
    if (!body || typeof body !== 'object' || !('budgetId' in body) || !('budget' in body) ||
      typeof body.budgetId !== 'number' || typeof body.budget !== 'number') return new HttpResponse(null, { status: 400 });
    const { budgetId, budget } = body;
    patches.push({ budgetId, budget });
    await holds.get(budgetId);
    if (failPatch) return HttpResponse.json({ message: 'synthetic failure' }, { status: 500 });
    rows = rows.map(row => row.id === budgetId ? { ...row, budget } : row);
    return HttpResponse.json({ budgetId, budget });
  }),
  http.get(`${ORIGIN}/api/ai/data/doojo`, ({ request }) => {
    monthQueries.push(new URL(request.url).search);
    return HttpResponse.json({ file_id: 'synthetic-fixture', doojo: sheets });
  }),
);
beforeAll(() => server.listen({ onUnhandledRequest(req, print) { unexpected.push(new URL(req.url).pathname); print.error(); } }));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-09T03:00:00Z'));
  unexpected = []; images = []; patches = []; monthQueries = []; holds = new Map(); releases = []; failPatch = false; animationReply = animation; lottie.values = [];
  rows = [{ id: 1, budget: 100000, spending: 200000, category: '식비', initialBudget: 100000 },
    { id: 2, budget: 50000, spending: 100000, category: '카페', initialBudget: 50000 }];
  sheets = [sheet(9, { 식비: prediction(300, true), 카페: prediction(150, true), 기타: prediction(null, null), 교육: prediction(0, false) })];
  const nativeFetch = globalThis.fetch;
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) =>
    nativeFetch(typeof input === 'string' ? new URL(input, ORIGIN) : input, init));
  vi.stubGlobal('Image', class {
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    set src(value: string) { images.push(value); queueMicrotask(() => this.onload?.()); }
  });
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } } });
  useAuthStore.setState({ accessToken: 'lint-synthetic-access' });
});
afterEach(async () => {
  cleanup(); releases.forEach(release => release()); await client.cancelQueries(); client.clear(); useAuthStore.getState().clear(); localStorage.clear();
  server.resetHandlers(); vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks();
  expect(unexpected).toEqual([]);
});
afterAll(() => server.close());
function mount(page: 'pot' | 'advice') {
  return render(<StrictMode><QueryClientProvider client={client}><MemoryRouter initialEntries={[page === 'pot' ? '/pot/9' : '/toadAdvice']}>
    <Routes><Route path="/pot/:month" element={<LeakPotPage />} /><Route path="/toadAdvice" element={<ToadAdvice />} /></Routes>
  </MemoryRouter></QueryClientProvider></StrictMode>);
}
async function slider(name: string) { return screen.findByRole('slider', { name: `${name} 한도` }); }
function change(input: HTMLElement, value: number) { fireEvent.change(input, { target: { value: String(value) } }); }

describe('legacy budget behavior through actual query, mutation and API', () => {
  it('debounces the latest amount, clears the completed pending value and preserves other pending IDs', async () => {
    const releaseFood = hold(1); const releaseCafe = hold(2);
    mount('pot'); const food = await slider('식비'); const cafe = await slider('카페');
    change(food, 125000); change(food, 150000); change(cafe, 75000);
    await waitFor(() => expect(patches).toHaveLength(2));
    expect(patches).toEqual([{ budgetId: 1, budget: 150000 }, { budgetId: 2, budget: 75000 }]);
    releaseFood();
    await waitFor(() => expect(client.isMutating()).toBe(1));
    await waitFor(() => expect(client.isFetching()).toBe(0));
    act(() => client.setQueryData(monthlyBudgetQueryKeys.monthly(2026, 9), rows.map(row => ({ ...row, budget: 20000 }))));
    await waitFor(() => { expect(food).toHaveValue('20000'); expect(cafe).toHaveValue('75000'); });
    releaseCafe();
    await waitFor(() => expect(client.isMutating()).toBe(0));
    await waitFor(() => expect(client.isFetching()).toBe(0));
    act(() => client.setQueryData(monthlyBudgetQueryKeys.monthly(2026, 9), rows.map(row => ({ ...row, budget: 20000 }))));
    await waitFor(() => { expect(food).toHaveValue('20000'); expect(cafe).toHaveValue('20000'); });
    expect(lottie.values).toContainEqual(animation);
    expect(images).toContain('/leakPot/pot.webp');
  });
  it('restores a failed mutation and removes its pending override', async () => {
    failPatch = true; mount('pot'); const food = await slider('식비');
    change(food, 150000); expect(food).toHaveValue('150000');
    await waitFor(() => expect(patches).toHaveLength(1));
    await waitFor(() => expect(food).toHaveValue('100000'));
    expect(rows[0].budget).toBe(100000);
  });
  it('cancels every outstanding debounce on unmount under StrictMode', async () => {
    const view = mount('pot'); const food = await slider('식비'); const cafe = await slider('카페');
    vi.useFakeTimers();
    change(food, 150000); change(cafe, 75000); view.unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(patches).toEqual([]);
  });
  it('does not render Lottie for a null animation response', async () => {
    animationReply = null; mount('pot'); await slider('식비');
    await waitFor(() => expect(screen.queryByTestId('lottie-data')).not.toBeInTheDocument());
    expect(lottie.values).toEqual([]);
  });
  it('uses the SVG hit test to dismiss the leak tooltip only outside the pot', async () => {
    const view = mount('pot'); await slider('식비');
    const crack = view.container.querySelector<SVGImageElement>('.crack');
    const svg = crack?.ownerSVGElement;
    const body = svg?.querySelector('path[fill="white"]');
    if (!crack || !svg || !body) throw new Error('Missing pot SVG');
    const container = crack.closest('.pot-visualization')!;
    vi.spyOn(container, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 60, 800, 500));
    vi.spyOn(svg, 'getBoundingClientRect').mockReturnValue(new DOMRect(340, 90, 500, 520));
    let inside = true;
    Object.defineProperty(svg, 'createSVGPoint', { value: () => ({ x: 0, y: 0, matrixTransform: () => ({ x: 10, y: 10 }) }) });
    Object.defineProperty(body, 'getScreenCTM', { value: () => ({ inverse: () => ({}) }) });
    Object.defineProperty(body, 'isPointInFill', { value: () => inside });
    fireEvent.mouseEnter(crack, { clientX: 10, clientY: 10 });
    expect(screen.getByText('식비: 100,000냥 누수')).toBeInTheDocument();
    fireEvent.mouseMove(svg, { clientX: 400, clientY: 120 });
    expect(screen.getByText('식비: 100,000냥 누수')).toHaveStyle({ left: '300px', top: '60px' });
    inside = false; fireEvent.mouseMove(svg, { clientX: 30, clientY: 30 });
    expect(screen.queryByText('식비: 100,000냥 누수')).not.toBeInTheDocument();
  });
});
describe('Doojo object/nullable contract through actual adapter and page', () => {
  it('keeps OAuth month queries unchanged when opening the on-page detail section', async () => {
    mount('advice'); await screen.findByRole('heading', { name: '식비' });
    const completedQueries = [...monthQueries];
    fireEvent.click(screen.getByRole('button', { name: '카테고리별 소비 조언 보기 ↓' }));
    expect(screen.getByRole('heading', { name: '9월 과소비 요약' })).toHaveFocus();
    expect(monthQueries).toEqual(completedQueries);
    const card = screen.getByRole('button', { name: /식비/ });
    fireEvent.click(card); fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(card).toHaveFocus();
  });
  it('keeps a usable empty-result destination for successful OAuth no-advice data', async () => {
    sheets = [sheet(9, { 식비: prediction(0, false) })]; mount('advice');
    await screen.findByText('축하하오! 과소비 항목이 없소!');
    fireEvent.click(screen.getByRole('button', { name: '이번 달 소비 결과 보기 ↓' }));
    expect(screen.getByRole('heading', { name: '9월 소비 확인 결과' })).toHaveFocus();
    expect(monthQueries).toEqual(['?year=2026&month=9']);
  });
  it('keeps amount arithmetic, nullable exclusions, ordering and modal detail', async () => {
    const view = mount('advice');
    await screen.findByRole('heading', { name: '식비' });
    expect([...view.container.querySelectorAll('.category-title')].map(node => node.textContent)).toEqual(['식비', '카페']);
    expect(screen.getAllByText('250냥')).toHaveLength(2);
    expect(screen.getAllByText('125%')).toHaveLength(2);
    fireEvent.click(screen.getByRole('heading', { name: '식비' }));
    expect(view.container.querySelector('.modal-detail')).toHaveTextContent('300냥');
    expect(view.container.querySelector('.modal-detail')).toHaveTextContent('200냥');
    expect(monthQueries).toEqual(['?year=2026&month=9']);
  });
  it.each(['single', 'matching', 'fallback'] as const)('keeps %s sheet selection', async kind => {
    const first = sheet(8, { 교육: prediction(400, true) });
    const current = sheet(9, { 식비: prediction(300, true) });
    sheets = kind === 'single' ? [first] : kind === 'matching' ? [first, current] : [first, sheet(7, {})];
    const view = mount('advice'); const expected = kind === 'matching' ? '식비' : '교육';
    await screen.findByRole('heading', { name: expected });
    expect([...view.container.querySelectorAll('.category-title')].map(node => node.textContent)).toEqual([expected]);
  });
  it('limits the first 12 matching categories before sorting, without promoting the 13th', async () => {
    sheets = [sheet(9, Object.fromEntries(Array.from({ length: 13 }, (_, index) => [`합성${index}`, prediction(200 + index * 100, true)])))];
    const view = mount('advice'); await screen.findByRole('heading', { name: '합성11' });
    expect([...view.container.querySelectorAll('.category-title')].map(node => node.textContent))
      .toEqual(Array.from({ length: 12 }, (_, index) => `합성${11 - index}`));
    expect(screen.queryByRole('heading', { name: '합성12' })).not.toBeInTheDocument();
  });
  it.each([{ items: [] }, { items: [sheet(9, { 기타: prediction(null, null), 식비: prediction(0, false) })] }])('shows no advice for empty/null/false results: %j', async ({ items }) => {
    sheets = items;
    mount('advice');
    await screen.findByText(items.length === 0 ? '데이터가 아직 준비되지 않았습니다.' : '축하하오! 과소비 항목이 없소!');
    expect(screen.queryByRole('heading', { name: '기타' })).not.toBeInTheDocument();
  });
});
