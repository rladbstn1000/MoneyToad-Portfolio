import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterEach, expect, it, vi } from 'vitest';
import ChartPage from '../../src/pages/ChartPage';
import { useAuthStore } from '../../src/store/authStore';
import { rendererState } from '../lintRendererState';
vi.mock('recharts', () => import('../lintRendererDouble'));
const ORIGIN = 'http://127.0.0.1:18080';
let client: QueryClient;
const requests: string[] = [];
const unexpected: string[] = [];
const server = setupServer(
  http.get(`${ORIGIN}/api/transactions`, ({ request }) => {
    requests.push(new URL(request.url).pathname);
    return HttpResponse.json(Array.from({ length: 12 }, (_, index) => ({ date: `2024-${String(index + 1).padStart(2, '0')}`,
      totalAmount: 1000, leaked: index === 11 })));
  }),
  http.get(`${ORIGIN}/api/transactions/2024/12`, ({ request }) => {
    requests.push(new URL(request.url).pathname);
    return HttpResponse.json([{ id: 1, transactionDateTime: '2024-12-01T12:00:00', amount: 1000,
      category: '식비', merchantName: '합성 demo 렌더 검증' }]);
  }),
  http.get(`${ORIGIN}/api/transactions/2024/12/categories`, ({ request }) => {
    requests.push(new URL(request.url).pathname);
    return HttpResponse.json([{ category: '식비', totalAmount: 1000, leakedAmount: 100 }]);
  }),
);
afterEach(async () => {
  cleanup(); await client.cancelQueries(); client.clear(); useAuthStore.getState().clear();
  server.close(); vi.useRealTimers(); expect(unexpected).toEqual([]);
});
it('uses the stored demo anchor in actual dots and omits peer tooltip/series/HTTP', async () => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-09T03:00:00Z'));
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  useAuthStore.setState({ accessToken: 'lint-synthetic-demo', status: 'authenticated', generation: 10,
    revision: 1, expiresAt: Date.now() + 3600000 });
  rendererState.tooltip = { active: true, label: '12월', payload: [
    { dataKey: 'me', value: 1000, payload: { leaked: true } }, { dataKey: 'peers', value: 999 },
  ] };
  rendererState.pieInput = { cx: 100, cy: 100, midAngle: 0, outerRadius: 50, percent: 1, name: '식비' };
  rendererState.dotOverride = undefined; rendererState.formatter = undefined;
  server.listen({ onUnhandledRequest(req, print) { unexpected.push(new URL(req.url).pathname); print.error(); } });
  render(<QueryClientProvider client={client}><MemoryRouter><ChartPage /></MemoryRouter></QueryClientProvider>);
  await waitFor(() => expect(screen.getByTestId('me-11').querySelector('image')).toHaveAttribute('xlink:href', '/charts/flower_gray.webp'));
  const dot = screen.getByTestId('me-11').firstElementChild; if (!dot) throw new Error('Missing stored month');
  fireEvent.click(dot); await screen.findByText('합성 demo 렌더 검증');
  expect(screen.getByTestId('actual-tooltip')).toHaveTextContent('내 소비 : 1,000원');
  expect(screen.getByTestId('actual-tooltip')).not.toHaveTextContent('또래');
  expect(screen.queryByTestId('dots-peers')).not.toBeInTheDocument();
  expect(requests).toEqual(['/api/transactions', '/api/transactions/2024/12', '/api/transactions/2024/12/categories']);
});
