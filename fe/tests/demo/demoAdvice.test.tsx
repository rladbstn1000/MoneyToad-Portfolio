import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import ToadAdvice from '../../src/pages/ToadAdvice';
import { useAuthStore } from '../../src/store/authStore';
import { buildDemoAdvice, preparedDemoComment } from '../../src/demo/demoAdvice';
import { monthlyBudgetQueryKeys, transactionQueryKeys } from '../../src/api/queryKeys';
import type { MonthlyBudgetResponse, MonthlyTransaction } from '../../src/types';

const origin = 'http://127.0.0.1:18080';
const row = (category: string, amount: number, merchantName: string, sequence = 1): MonthlyTransaction => ({
  id: sequence, category, amount, merchantName, transactionDateTime: '2024-12-01T00:00:00',
});
const budget = (category: string, spending: number, limit: number): MonthlyBudgetResponse => ({
  id: 1, category, spending, budget: limit, initialBudget: 0,
});
let client: QueryClient;
let paths: string[];
let unexpected: number;
let monthBudgets: MonthlyBudgetResponse[];
let monthRows: MonthlyTransaction[];
let failMonthly: boolean;
let failYear: boolean;
let holdYear: Promise<void> | undefined;
let releaseYear: (() => void) | undefined;
const annual = Array.from({ length: 12 }, (_, index) => ({ date: `2024-${String(index + 1).padStart(2, '0')}`, totalAmount: index === 11 ? 908000 : 802000, leaked: index === 6 || index === 11 }));
const server = setupServer(
  http.get(`${origin}/api/transactions`, async ({ request }) => {
    paths.push(new URL(request.url).pathname); await holdYear;
    return failYear ? new HttpResponse(null, { status: 503 }) : HttpResponse.json(annual);
  }),
  http.get(`${origin}/api/budgets/:year/:month`, ({ request, params }) => {
    paths.push(new URL(request.url).pathname);
    if (failMonthly) return new HttpResponse(null, { status: 503 });
    return HttpResponse.json(params.month === '7' ? [budget('문화생활', 180000, 60000)] : monthBudgets);
  }),
  http.get(`${origin}/api/transactions/:year/:month`, ({ request, params }) => {
    paths.push(new URL(request.url).pathname);
    if (failMonthly) return new HttpResponse(null, { status: 503 });
    return HttpResponse.json(params.month === '7' ? [row('문화생활', 180000, '합성 문화 01')] : monthRows);
  }),
);
beforeAll(() => server.listen({ onUnhandledRequest() { unexpected++; throw new Error('Unexpected advice request'); } }));
afterAll(() => server.close());
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2030-01-02T00:00:00Z'));
  paths = []; unexpected = 0; failMonthly = false; failYear = false; holdYear = undefined; releaseYear = undefined;
  monthBudgets = [budget('카페', 58000, 40000)];
  monthRows = [row('카페', 30000, '합성 장보기(분류 연습)'), ...Array.from({ length: 7 }, (_, index) => row('카페', 4000, `합성 카페 ${index + 1}`, index + 2))];
  useAuthStore.setState({ status: 'authenticated', accessToken: 'synthetic-advice-access', expiresAt: Date.now() + 3600000, generation: 10, revision: 1 });
  client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false, gcTime: Infinity }, mutations: { retry: false } } });
});
afterEach(async () => {
  cleanup(); releaseYear?.(); await client.cancelQueries(); client.clear(); useAuthStore.getState().clear();
  server.resetHandlers(); vi.useRealTimers(); expect(unexpected).toBe(0);
});
function mount() { return render(<QueryClientProvider client={client}><MemoryRouter><ToadAdvice /></MemoryRouter></QueryClientProvider>); }

describe('prepared demo advice with actual authenticated query/API layer', () => {
  it('keeps queried cards below the overview in document flow before navigation', async () => {
    mount();
    const card = await screen.findByRole('button', { name: /카페.*냥|냥.*카페/ });
    const overview = screen.getByRole('region', { name: '두꺼비의 소비내역 조언소' });
    const details = screen.getByRole('region', { name: '12월 과소비 요약' });
    expect(overview.nextElementSibling).toBe(details);
    expect(overview.lastElementChild).toBe(screen.getByRole('button', { name: '카테고리별 소비 조언 보기 ↓' }).parentElement);
    expect(details).toContainElement(card);
    expect(overview).not.toContainElement(card);
    const completedPaths = [...paths];
    fireEvent.click(screen.getByRole('button', { name: '카테고리별 소비 조언 보기 ↓' }));
    expect(screen.getByRole('button', { name: /카페.*냥|냥.*카페/ })).toBe(card);
    expect(paths).toEqual(completedPaths);
  });
  it('moves to the current detail heading without another remote request', async () => {
    mount(); await screen.findByRole('heading', { name: '카페' });
    const completedPaths = [...paths];
    fireEvent.click(screen.getByRole('button', { name: '카테고리별 소비 조언 보기 ↓' }));
    expect(screen.getByRole('heading', { name: '12월 과소비 요약' })).toHaveFocus();
    expect(paths).toEqual(completedPaths);
  });
  it('links successful empty remote data to a real result instead of announcing absent cards', async () => {
    monthBudgets = []; monthRows = []; mount();
    await screen.findByText('축하하오! 과소비 항목이 없소!');
    const completedPaths = [...paths];
    expect(screen.queryByRole('button', { name: '카테고리별 소비 조언 보기 ↓' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '이번 달 소비 결과 보기 ↓' }));
    expect(screen.getByRole('heading', { name: '12월 소비 확인 결과' })).toHaveFocus();
    expect(paths).toEqual(completedPaths);
  });
  it('uses the stored twelve months and actual money without any AI or budget-year request', async () => {
    const view = mount(); await screen.findByRole('heading', { name: '카페' });
    expect(view.container.querySelector('[data-demo-page="advice"]')).not.toBeNull();
    expect(screen.getAllByText('18,000냥')).toHaveLength(2);
    expect(screen.getByText(/미리 생성된 분석 결과/)).toBeInTheDocument();
    expect(paths).toEqual(expect.arrayContaining(['/api/transactions', '/api/transactions/2024/12', '/api/budgets/2024/12']));
    expect(paths.some(path => path.includes('/ai/') || path === '/api/budgets' || path.includes('2030'))).toBe(false);
  });
  it('uses actual month navigation for M-5 culture advice and accessible modal controls', async () => {
    mount(); await screen.findByRole('heading', { name: '카페' });
    fireEvent.click(screen.getByRole('button', { name: '누수 7월' }));
    const card = await screen.findByRole('button', { name: /문화생활/ });
    card.focus(); fireEvent.click(card);
    const dialog = screen.getByRole('dialog', { name: '문화생활' });
    expect(dialog).toHaveTextContent('120,000냥');
    expect(dialog).toHaveTextContent('180,000냥');
    expect(dialog).toHaveTextContent('60,000냥');
    expect(dialog).toHaveTextContent('샘플 분석을 바탕으로 한 두꺼비의 코멘트');
    expect(screen.getByRole('button', { name: '조언 상세 닫기' })).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(card).toHaveFocus();
  });
  it('never leaves the old cafe advice after the actual budget/transaction queries revalidate', async () => {
    mount(); await screen.findByRole('heading', { name: '카페' });
    monthBudgets = [budget('카페', 28000, 40000), budget('마트 / 편의점', 120000, 150000)];
    monthRows = monthRows.map(item => item.amount === 30000 ? { ...item, category: '마트 / 편의점' } : item);
    await act(async () => { await Promise.all([
      client.invalidateQueries({ queryKey: monthlyBudgetQueryKeys.all }),
      client.invalidateQueries({ queryKey: transactionQueryKeys.all }),
    ]); });
    await screen.findByText('축하하오! 과소비 항목이 없소!');
    expect(screen.queryByRole('heading', { name: '카페' })).not.toBeInTheDocument();
  });
  it('updates current overage when the real budget changes and does not overwrite it with a reference', async () => {
    mount(); await screen.findByRole('heading', { name: '카페' });
    monthBudgets = [budget('카페', 58000, 50000)];
    await act(async () => { await client.invalidateQueries({ queryKey: monthlyBudgetQueryKeys.all }); });
    await waitFor(() => expect(screen.getAllByText('8,000냥')).toHaveLength(2));
  });
  it.each(['year', 'month'] as const)('shows the %s API error without substituting prepared money', async kind => {
    failYear = kind === 'year'; failMonthly = kind === 'month'; mount();
    await screen.findByText('데이터가 아직 준비되지 않았습니다.');
    expect(screen.queryByRole('heading', { name: '카페' })).not.toBeInTheDocument();
  });
  it('waits for the period before requesting protected monthly data', async () => {
    holdYear = new Promise<void>(resolve => { releaseYear = resolve; }); mount();
    await screen.findByText('데이터를 불러오는 중...');
    await waitFor(() => expect(paths).toEqual(['/api/transactions']));
    releaseYear?.(); await screen.findByRole('heading', { name: '카페' });
  });
  it('represents successful empty data honestly', async () => {
    monthBudgets = []; monthRows = []; mount(); await screen.findByText('축하하오! 과소비 항목이 없소!');
    expect(screen.queryByRole('heading', { name: '카페' })).not.toBeInTheDocument();
  });
});

describe('demo advice without a persisted comparison budget', () => {
  it.each(['카페', '교육'])('keeps the %s null-ID amount without inventing a zero budget or reference comparison', category => {
    const output = buildDemoAdvice([{ ...budget(category, 10000, 0), id: null }], [row(category, 10000, '합성 상점')]);
    expect(output.advices).toHaveLength(1);
    expect(output.advices[0]).toMatchObject({ basis: 'missing', spending: 10000, over: 0, pct: null });
    expect(output.advices[0].detail).toContain('비교 기준 없음');
    expect(output.advices[0].detail).not.toContain('현재 한도는');
    expect(output.advices[0].detail).not.toContain('현재 누수는');
    expect(preparedDemoComment(category, false)).toContain('과소비 여부를 단정하지 않고');
  });
  it.each(['카페', '교육'])('renders actual %s spending, a missing-basis notice and general advice through the API', async category => {
    monthBudgets = [{ ...budget(category, 10000, 0), id: null }];
    monthRows = [row(category, 10000, '합성 상점')];
    const view = mount();
    const card = await screen.findByRole('button', { name: new RegExp(`실제 소비 10,000냥.*비교 기준 없음.*${category}`) });
    expect([...view.container.querySelectorAll('.stats-card .stat-number')].map(element => element.textContent)).toEqual(['0개', '0냥', '—']);
    expect(screen.getByText(/비교 기준 없음: 1개/)).toBeInTheDocument();
    expect(card.querySelector('.severity-badge')).toBeNull();
    expect(card.querySelector('.progress-container')).toBeNull();
    fireEvent.click(card);
    const dialog = screen.getByRole('dialog', { name: category });
    expect(dialog).toHaveTextContent('실제 소비: 10,000냥');
    expect(dialog).toHaveTextContent('과소비 여부를 단정하지 않고');
    expect(dialog).not.toHaveTextContent('현재 한도는');
    expect(dialog).not.toHaveTextContent('과소비:');
  });
  it('excludes unbased spending from mixed overage counts and totals', async () => {
    monthBudgets.push({ ...budget('교육', 10000, 0), id: null });
    monthRows.push(row('교육', 10000, '합성 교육'));
    const view = mount(); await screen.findByRole('heading', { name: '교육' });
    const values = [...view.container.querySelectorAll('.stats-card .stat-number')].map(element => element.textContent);
    expect(values.slice(0, 2)).toEqual(['1개', '18,000냥']);
    expect(screen.getByText(/비교 기준 없음: 1개/)).toBeInTheDocument();
  });
});

describe('deterministic current-data advice model', () => {
  it('counts exact merchants without turning category frequency into merchant frequency', () => {
    const output = buildDemoAdvice(monthBudgets, monthRows);
    expect(output.details.카페.mostFrequent.count).toBe(1);
    expect(output.details.카페.mostSpent.amount).toBe(30000);
    expect(buildDemoAdvice(monthBudgets, [...monthRows].reverse())).toEqual(output);
  });
  it('preserves real repeated merchant counts and selects maximum spending deterministically', () => {
    const output = buildDemoAdvice(monthBudgets, [row('카페', 4000, '같은 합성 상점'), row('카페', 5000, '같은 합성 상점', 2), row('카페', 10000, '다른 합성 상점', 3)]);
    expect(output.details.카페.mostFrequent).toEqual({ merchant: '같은 합성 상점', count: 2, totalAmount: 9000 });
    expect(output.details.카페.mostSpent.merchant).toBe('다른 합성 상점');
  });
  it('marks an undefined reference instead of inventing a comparison', () => {
    const output = buildDemoAdvice([budget('교육', 10000, 0)], [row('교육', 10000, '합성 교육')]);
    expect(output.advices[0].pct).toBeNull();
    expect(output.advices[0].over).toBe(10000); // A positive persisted ID with a genuine zero limit is comparable.
    expect(output.advices[0].detail).toContain('샘플 기준 평균은 제공하지 않습니다');
    expect(preparedDemoComment('교육')).toContain('제공하지 않소');
  });
  it('keeps current spend/limits separate from the original reference and does not mutate inputs', () => {
    const before = JSON.stringify({ monthBudgets, monthRows });
    const output = buildDemoAdvice(monthBudgets, monthRows);
    expect(output.advices[0].over).toBe(18000);
    expect(output.advices[0].pct).toBeCloseTo((58000 - 410000 / 12) / (410000 / 12) * 100);
    expect(JSON.stringify({ monthBudgets, monthRows })).toBe(before);
  });
});
