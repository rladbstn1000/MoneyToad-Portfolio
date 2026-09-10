import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import ChartPage from '../../src/pages/ChartPage';
import { useAuthStore } from '../../src/store/authStore';
import type { MonthlyTransaction, TransactionYear } from '../../src/types';

vi.mock('recharts', () => import('../rechartsDouble'));

const ORIGIN = 'http://127.0.0.1:18080';
const BUDGETS: Record<string, number> = {
  '주거 / 통신': 600000, '교통 / 차량': 80000, '식비': 200000,
  '카페': 40000, '마트 / 편의점': 150000, '문화생활': 60000,
};
type RequestRecord = { method: string; path: string; body?: unknown };
type Reply = () => Response | Promise<Response>;
let requests: RequestRecord[];
let unexpected: number;
let rows: MonthlyTransaction[];
let yearReply: Reply;
let anchor: string;
let queryClient: QueryClient;

function seededMonth(date: string): MonthlyTransaction[] {
  const fixture: [string, number][] = [
    ['주거 / 통신', 500000],
    ...Array.from({ length: 4 }, (): [string, number] => ['교통 / 차량', 15000]),
    ...Array.from({ length: 4 }, (): [string, number] => ['식비', 45000]),
    ...Array.from({ length: 7 }, (): [string, number] => ['카페', 4000]),
    ['카페', 30000], ['마트 / 편의점', 45000], ['마트 / 편의점', 45000], ['문화생활', 20000],
  ];
  return fixture.map(([category, amount], index) => ({ id: index + 1,
    transactionDateTime: `${date}-01T00:00:00`, amount, category,
    merchantName: index === 16 ? '합성 장보기(분류 연습)' : `합성 가맹점 ${index + 1}` }));
}

function categories() {
  return Object.entries(BUDGETS).map(([category, budget]) => {
    const totalAmount = rows.filter(row => row.category === category).reduce((sum, row) => sum + row.amount, 0);
    return { category, totalAmount, leakedAmount: Math.max(0, totalAmount - budget) };
  });
}

function annual(): TransactionYear[] {
  const [year, month] = anchor.split('-').map(Number);
  const last = year * 12 + month - 1;
  return Array.from({ length: 12 }, (_, index) => {
    const ordinal = last - 11 + index;
    return { date: `${Math.floor(ordinal / 12)}-${String(ordinal % 12 + 1).padStart(2, '0')}`,
      totalAmount: index === 11 ? rows.reduce((sum, row) => sum + row.amount, 0)
        : index === 6 ? 962000 : index === 9 ? 842000 : index === 10 ? 862000 : 802000,
      leaked: index === 6 || index === 11 && categories().some(row => row.leakedAmount > 0) };
  });
}

async function record(request: Request, response: (body: unknown) => Response | Promise<Response>) {
  const body: unknown = request.method === 'PATCH' ? await request.json() : undefined;
  requests.push({ path: new URL(request.url).pathname, method: request.method, ...(body === undefined ? {} : { body }) });
  return response(body);
}

const server = setupServer(
  http.get(`${ORIGIN}/api/transactions`, ({ request }) => record(request, () => yearReply())),
  http.get(`${ORIGIN}/api/transactions/peer`, ({ request }) => record(request, () => HttpResponse.json([]))),
  http.get(`${ORIGIN}/api/transactions/:year/:month/categories`, ({ request }) => record(request, () => HttpResponse.json(categories()))),
  http.get(`${ORIGIN}/api/transactions/:year/:month`, ({ request }) => record(request, () => HttpResponse.json(rows))),
  http.patch(`${ORIGIN}/api/transactions/:id/category`, ({ request, params }) => record(request, body => {
    if (!body || typeof body !== 'object' || !('category' in body) || typeof body.category !== 'string') {
      return new HttpResponse(null, { status: 400 });
    }
    const target = rows.find(row => row.id === Number(params.id));
    if (!target) return new HttpResponse(null, { status: 404 });
    target.category = body.category;
    return HttpResponse.json(target);
  })),
);

beforeAll(() => server.listen({ onUnhandledRequest() { unexpected++; throw new Error('Unexpected request outside the seed HTTP double'); } }));
afterAll(() => server.close());
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  // The browser is deliberately more than a year ahead of the stored visitor.
  vi.setSystemTime(new Date('2026-01-01T00:30:00Z'));
  anchor = '2024-12'; rows = seededMonth(anchor); requests = []; unexpected = 0;
  yearReply = () => HttpResponse.json(annual());
  useAuthStore.setState({ status: 'authenticated', accessToken: 'synthetic-seed-access',
    expiresAt: Date.now() + 3600000, generation: 10, revision: 1 });
  queryClient = new QueryClient({ defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: false, gcTime: Infinity }, mutations: { retry: false },
  } });
});
afterEach(async () => {
  cleanup(); await queryClient.cancelQueries(); queryClient.clear();
  useAuthStore.getState().clear(); server.resetHandlers(); vi.useRealTimers();
  expect(unexpected).toBe(0);
  console.info('DEMO_CHART_SEED_FE ' + JSON.stringify({ test: expect.getState().currentTestName,
    layer: 'actual_chart_hooks_mutation_api_msw', requests, unexpected }));
});

function mount() {
  const user = userEvent.setup();
  render(<QueryClientProvider client={queryClient}><MemoryRouter><ChartPage /></MemoryRouter></QueryClientProvider>);
  return user;
}
function count(path: string) { return requests.filter(request => request.path === path).length; }
function monthRequests() { return requests.filter(request => /^\/api\/transactions\/\d+\/\d+/.test(request.path)); }
function lineData(): { me: number | null; leaked: boolean; isLastYear: boolean }[] {
  return JSON.parse(screen.getByTestId('chart-line-data').textContent ?? '[]');
}
async function settled() {
  await waitFor(() => expect(queryClient.isFetching() + queryClient.isMutating()).toBe(0));
  await act(async () => { await Promise.resolve(); });
}

describe('demo seeded Chart over actual query / mutation / HTTP functions', () => {
  it('uses persisted API months, hides peer traffic, and reaggregates the 30000 category correction', async () => {
    const user = mount();
    await screen.findByText(/기준월 2024-12/);
    expect(screen.getByText(/합성 소비.*기준 예산.*AI 예측이 아닙니다/)).toBeInTheDocument();
    expect(screen.getByText('또래 비교 데이터는 이번 체험에서 제공하지 않습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /또래 소비 .* 선택/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/또래 소비를 불러오는 중/)).not.toBeInTheDocument();
    expect(lineData().reduce((sum, point) => sum + (point.me ?? 0), 0)).toBe(9990000);
    expect(lineData()[11]).toMatchObject({ me: 908000, leaked: true, isLastYear: false });
    const december = screen.getByRole('button', { name: '내 소비 12월 선택' });
    expect(december.querySelector('image')?.getAttribute('xlink:href')).toContain('flower_gray.webp');
    await user.click(december);
    const row = await screen.findByRole('row', { name: /합성 장보기\(분류 연습\)/ });
    expect(count('/api/transactions/2024/12')).toBe(1);
    expect(screen.getByText(/총 소비 금액:/)).toHaveTextContent('908,000원');
    await waitFor(() => expect(screen.getByText(/누수 금액:/)).toHaveTextContent('18,000원'));
    await settled();
    await user.click(within(row).getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: '마트 / 편의점' }));
    await waitFor(() => expect(screen.getByText(/누수 금액:/)).toHaveTextContent('누수 금액: 0원'));
    await settled();
    expect(screen.getByText(/총 소비 금액:/)).toHaveTextContent('908,000원');
    expect(lineData()[11]).toMatchObject({ me: 908000, leaked: false });
    expect(screen.getByRole('button', { name: '내 소비 12월 선택' }).querySelector('image')?.getAttribute('xlink:href')).toContain('flower.webp');
    expect(requests.filter(request => request.method === 'PATCH')).toEqual([
      { method: 'PATCH', path: '/api/transactions/17/category', body: { category: '마트 / 편의점' } },
    ]);
    expect(count('/api/transactions')).toBe(2);
    expect(count('/api/transactions/2024/12')).toBe(2);
    expect(count('/api/transactions/2024/12/categories')).toBe(2);
    expect(count('/api/transactions/peer')).toBe(0);
  });

  it('sends no month queries before the annual period is confirmed', async () => {
    let release!: (response: Response) => void;
    yearReply = () => new Promise<Response>(resolve => { release = resolve; });
    mount();
    await waitFor(() => expect(count('/api/transactions')).toBe(1));
    fireEvent.click(screen.getByRole('button', { name: '내 소비 12월 선택' }));
    expect(monthRequests()).toEqual([]);
    expect(screen.queryByRole('heading', { name: '12월 상세' })).not.toBeInTheDocument();
    release(HttpResponse.json(annual()));
    await screen.findByText(/기준월 2024-12/);
    expect(monthRequests()).toEqual([]);
    expect(count('/api/transactions/peer')).toBe(0);
  });

  it.each(['empty', 'gap', 'duplicate', 'invalid-month', 'not-array'] as const)('rejects %s annual data without a browser-month fallback or editable table', async kind => {
    yearReply = () => {
      const result = annual();
      if (kind === 'empty') return HttpResponse.json([]);
      if (kind === 'not-array') return HttpResponse.json({ date: anchor });
      if (kind === 'gap') result.splice(4, 1);
      if (kind === 'duplicate') result[4].date = result[3].date;
      if (kind === 'invalid-month') result[4].date = '2024-13';
      return HttpResponse.json(result);
    };
    const user = mount();
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: '내 소비 12월 선택' }));
    expect(monthRequests()).toEqual([]);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(lineData().every(point => point.me === null)).toBe(true);
    expect(count('/api/transactions/peer')).toBe(0);
  });

  it('maps February to the prior API year and keeps January anchored after the browser month rolls over', async () => {
    vi.setSystemTime(new Date('2026-01-31T23:59:00Z'));
    useAuthStore.setState({ expiresAt: Date.now() + 3600000 });
    anchor = '2025-01'; rows = seededMonth(anchor);
    const user = mount();
    await screen.findByText(/기준월 2025-01/);
    expect(lineData()[1].isLastYear).toBe(true);
    await user.click(screen.getByRole('button', { name: '내 소비 2월 선택' }));
    await waitFor(() => expect(count('/api/transactions/2024/2')).toBe(1));
    // Force another render after a browser calendar transition. The stored
    // twelve API months must still determine both the click and edit period.
    vi.setSystemTime(new Date('2026-02-01T00:01:00Z'));
    await user.click(screen.getByRole('button', { name: '내 소비 1월 선택' }));
    await waitFor(() => expect(count('/api/transactions/2025/1')).toBe(1));
    expect(screen.getByText(/기준월 2025-01/)).toBeInTheDocument();
    expect(monthRequests().some(request => request.path.includes('/2026/'))).toBe(false);
    expect(count('/api/transactions/peer')).toBe(0);
  });
});
