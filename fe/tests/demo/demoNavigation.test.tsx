import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from 'vitest';
import DemoHeader from '../../src/components/DemoHeader';
import DemoPeriodEntry from '../../src/demo/DemoPeriodEntry';
import { useAuthStore } from '../../src/store/authStore';

const origin = 'http://127.0.0.1:18080';
let calls: string[] = [], fail = false;
const server = setupServer(http.get(`${origin}/api/transactions`, ({ request }) => {
  calls.push(new URL(request.url).pathname);
  return fail ? new HttpResponse(null, { status: 503 }) : HttpResponse.json(Array.from({ length: 12 }, (_, index) => ({
    date: `${index < 9 ? 2025 : 2026}-${String((index + 3) % 12 + 1).padStart(2, '0')}`,
    totalAmount: 0, leaked: false,
  })));
}));
let client: QueryClient;
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterAll(() => server.close());
beforeEach(() => {
  calls = []; fail = false;
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  useAuthStore.setState({ status: 'authenticated', accessToken: 'synthetic-navigation', generation: 20, revision: 1, operation: null });
});
afterEach(() => { cleanup(); client.clear(); });

it('keeps all original demo labels and closes the accessible menu with Escape and route navigation', async () => {
  render(<MemoryRouter><DemoHeader /></MemoryRouter>);
  const toggle = screen.getByRole('button', { name: '체험 메뉴' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(toggle); expect(toggle).toHaveAttribute('aria-expanded', 'true');
  const names = screen.getAllByRole('link').map(link => link.textContent);
  expect(names).toEqual(['마당', '콩쥐의 장독대', '콩쥐의 씀씀이', '두꺼비의 조언', '콩쥐의 곳간']);
  await userEvent.keyboard('{Escape}');
  expect(toggle).toHaveAttribute('aria-expanded', 'false'); expect(toggle).toHaveFocus();
  fireEvent.click(toggle); fireEvent.click(screen.getByRole('link', { name: '콩쥐의 곳간' }));
  await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'));
  expect(screen.getByRole('link', { name: '콩쥐의 장독대' })).toHaveAttribute('href', '/pot');
  expect(calls).toEqual([]);
});

it('resolves the stored annual anchor without using the browser month or another login', async () => {
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/pot']}><Routes>
    <Route path="/pot" element={<DemoPeriodEntry />} /><Route path="/pot/3" element={<p>stored March pot</p>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  await screen.findByText('stored March pot');
  expect(calls).toEqual(['/api/transactions']);
  expect(useAuthStore.getState().status).toBe('authenticated');
});

it('keeps the session when period loading fails and retries only the period GET', async () => {
  fail = true;
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/pot']}><Routes>
    <Route path="/pot" element={<DemoPeriodEntry />} /><Route path="/pot/3" element={<p>stored March pot</p>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  const retry = await screen.findByRole('button', { name: '데이터 다시 시도' });
  expect(useAuthStore.getState().status).toBe('authenticated');
  expect(useAuthStore.getState().generation).toBe(20);
  fail = false; await act(async () => { fireEvent.click(retry); });
  await screen.findByText('stored March pot');
  expect(calls).toEqual(['/api/transactions', '/api/transactions']);
});
