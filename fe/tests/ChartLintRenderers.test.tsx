import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import ChartPage from '../src/pages/ChartPage';
import { useAuthStore } from '../src/store/authStore';
import { rendererState } from './lintRendererState';
vi.mock('recharts', () => import('./lintRendererDouble'));
const ORIGIN = 'http://127.0.0.1:18080';
let client: QueryClient;
let unexpected: string[];
let monthRequests: string[];
const server = setupServer(
  http.get(`${ORIGIN}/api/transactions`, () => HttpResponse.json([
    { date: '2026-09', totalAmount: 1000, leaked: true },
    { date: '2025-10', totalAmount: 500, leaked: false },
  ])),
  http.get(`${ORIGIN}/api/transactions/peer`, () => HttpResponse.json([{ date: '2026-09', totalAmount: 900 }])),
  http.get(`${ORIGIN}/api/transactions/:year/:month/categories`, () => HttpResponse.json([
    { category: '식비', totalAmount: 1000, leakedAmount: 100 },
  ])),
  http.get(`${ORIGIN}/api/transactions/:year/:month`, ({ params }) => {
    monthRequests.push(`${params.year}/${params.month}`);
    return HttpResponse.json([{ id: 91, transactionDateTime: `${params.year}-${String(params.month).padStart(2, '0')}-01T12:00:00`,
      amount: 1000, merchantName: '합성 렌더 검증', category: '식비' }]);
  }),
);
beforeAll(() => server.listen({ onUnhandledRequest(req, print) { unexpected.push(new URL(req.url).pathname); print.error(); } }));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-09T03:00:00Z'));
  unexpected = []; monthRequests = [];
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } } });
  useAuthStore.setState({ accessToken: 'lint-synthetic-access' });
  rendererState.tooltip = { active: true, label: '검증 월', payload: [
    { dataKey: 'me', value: 1000, payload: { leaked: true } }, { dataKey: 'peers', value: 900 },
  ] };
  rendererState.pieInput = { cx: 100, cy: 100, midAngle: 0, outerRadius: 50, percent: .03, name: '식비' };
  rendererState.dotOverride = undefined; rendererState.formatter = undefined;
});
afterEach(async () => {
  cleanup(); await client.cancelQueries(); client.clear(); useAuthStore.getState().clear(); localStorage.clear();
  vi.useRealTimers(); server.resetHandlers(); expect(unexpected).toEqual([]);
});
afterAll(() => server.close());
async function mount() {
  const override = rendererState.dotOverride;
  rendererState.dotOverride = undefined;
  const tree = <QueryClientProvider client={client}><MemoryRouter><ChartPage /></MemoryRouter></QueryClientProvider>;
  const view = render(tree);
  const month = await screen.findByTestId('me-8');
  const dot = month.firstElementChild; if (!dot) throw new Error('Missing initial month dot');
  fireEvent.click(dot);
  await screen.findByText('합성 렌더 검증');
  rendererState.dotOverride = override;
  view.rerender(<QueryClientProvider client={client}><MemoryRouter><ChartPage /></MemoryRouter></QueryClientProvider>);
}
describe('actual Chart renderer callbacks', () => {
  it('renders both tooltip amounts, leak marker, geometry and leaked pie color', async () => {
    await mount();
    const tooltip = screen.getByTestId('actual-tooltip');
    expect(tooltip).toHaveTextContent('내 소비 : 1,000원'); expect(tooltip).toHaveTextContent('또래 소비 : 900원');
    expect(tooltip).toHaveTextContent('[누수]');
    await waitFor(() => expect(screen.getByTestId('actual-pie-label')).toHaveTextContent('식비 3%'));
    const label = screen.getByTestId('actual-pie-label').querySelector('text');
    expect(label).toHaveAttribute('x', '202'); expect(label).toHaveAttribute('y', '100');
    expect(label).toHaveAttribute('fill', '#EC6665');
    expect(rendererState.formatter?.(0, '식비')).toEqual(['0원', '식비']);
    expect(rendererState.formatter?.(1234, '식비')).toEqual(['1,234원', '식비']);
    expect(screen.getAllByTestId('pie-cell').length).toBeGreaterThan(0);
  });
  it('keeps zero distinct from absent or malformed tooltip values', async () => {
    rendererState.tooltip.payload = [{ dataKey: 'me', value: 0 }, { dataKey: 'peers', value: { invalid: true } }, null];
    await mount(); const tooltip = screen.getByTestId('actual-tooltip');
    expect(tooltip).toHaveTextContent('내 소비 : 0원'); expect(tooltip).toHaveTextContent('또래 소비 : -원');
    expect(tooltip).not.toHaveTextContent('[누수]');
  });
  it.each([{ payload: [] }, { payload: [null, {}, { dataKey: 'me' }] }])('handles empty or missing tooltip data: %j', async ({ payload }) => {
    rendererState.tooltip.payload = payload; await mount();
    expect(screen.getByTestId('actual-tooltip')).not.toHaveTextContent('NaN');
  });
  it.each([.029, NaN])('omits invalid or under-3%% geometry: %s', async percent => {
    rendererState.pieInput.percent = percent; await mount();
    expect(screen.getByTestId('actual-pie-label').querySelector('text')).toBeNull();
  });
  it('preserves non-leaking pie label color', async () => {
    rendererState.pieInput.name = '나머지'; await mount();
    expect(screen.getByTestId('actual-pie-label').querySelector('text')).toHaveAttribute('fill', '#212A2D');
  });
  it('preserves current/leaked/previous-year dot images and peer click', async () => {
    await mount();
    expect(screen.getByTestId('me-8').querySelector('image')).toHaveAttribute('xlink:href', '/charts/flower_gray.webp');
    expect(screen.getByTestId('me-9').querySelector('image')).toHaveAttribute('xlink:href', '/charts/leaf.webp');
    expect(screen.getByTestId('me-9').firstElementChild).toHaveStyle({ opacity: '.6' });
    const peer = screen.getByTestId('peers-9').firstElementChild; if (!peer) throw new Error('Missing peer dot');
    fireEvent.click(peer); await waitFor(() => expect(monthRequests).toContain('2025/10'));
  });
  it('uses payload index only when index is absent', async () => {
    rendererState.dotOverride = { index: undefined, payload: { idx: 1 } }; await mount();
    const dot = screen.getByTestId('me-0').firstElementChild; if (!dot) throw new Error('Missing dot');
    fireEvent.click(dot); await waitFor(() => expect(monthRequests).toContain('2026/2'));
  });
  it('prefers a valid explicit index over a different payload index', async () => {
    rendererState.dotOverride = { index: 1, payload: { idx: 2 } }; await mount();
    const dot = screen.getByTestId('me-0').firstElementChild; if (!dot) throw new Error('Missing dot');
    fireEvent.click(dot); await waitFor(() => expect(monthRequests).toContain('2026/2'));
    expect(monthRequests).not.toContain('2026/3');
  });
  it('omits dots with missing coordinates instead of producing invalid SVG', async () => {
    rendererState.dotOverride = { cx: undefined }; await mount();
    expect(screen.getByTestId('me-0').querySelector('image')).toBeNull();
    expect(screen.getByTestId('peers-0').querySelector('circle')).toBeNull();
    expect(screen.getByTestId('me-0')).not.toHaveTextContent('NaN');
  });
  it.each([99, -1, 1.5, NaN])('rejects invalid explicit index without using payload fallback: %s', async index => {
    rendererState.dotOverride = { index, payload: { idx: 1 } }; await mount(); const before = [...monthRequests];
    const dot = screen.getByTestId('me-0').firstElementChild; if (!dot) throw new Error('Missing dot');
    fireEvent.click(dot); expect(monthRequests).toEqual(before);
  });
});
