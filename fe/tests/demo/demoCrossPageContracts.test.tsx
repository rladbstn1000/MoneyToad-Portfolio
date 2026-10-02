import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import App from '../../src/App';
import { useUpdateTransactionCategoryMutation } from '../../src/api/mutation/transactionMutation';
import { monthlyBudgetQueryKeys, transactionQueryKeys } from '../../src/api/queryKeys';
import { useAuthStore } from '../../src/store/authStore';
import { chartAssets, scrollLandingAssets } from '../../src/assets/pageAssets';

vi.mock('lottie-react', () => ({ default: () => null }));

const origin = 'http://127.0.0.1:18080';
let client: QueryClient;
let release: (() => void) | undefined;
let gate: Promise<void> | undefined;
let patches: unknown[];
let status: number;
const server = setupServer(http.patch(`${origin}/api/transactions/:id/category`, async ({ request }) => {
  patches.push(await request.json());
  await gate;
  return status === 200 ? HttpResponse.json({ id: 1, transactionDateTime: '2024-12-01T00:00:00',
    merchantName: '합성 장보기', amount: 30000, category: '마트 / 편의점' }) : new HttpResponse(null, { status });
}));
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterAll(() => server.close());
beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  patches = []; status = 200; gate = undefined; release = undefined;
  useAuthStore.setState({ status: 'authenticated', accessToken: 'synthetic-cross-page-access',
    operation: null, generation: 80, revision: 1, expiresAt: Date.now() + 3600000 });
});
afterEach(async () => {
  cleanup(); release?.(); await client.cancelQueries(); client.clear();
  useAuthStore.getState().clear(); server.resetHandlers(); vi.restoreAllMocks(); vi.unstubAllGlobals();
});
const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
const variables = { transactionId: 1, data: { category: '마트 / 편의점' } };
function primeCache(target = client) {
  target.setQueryData(monthlyBudgetQueryKeys.monthly(2024, 12), ['confirmed-budget']);
  target.setQueryData(transactionQueryKeys.categories(2024, 12), ['confirmed-category']);
}

describe('category mutation cross-page contracts through actual HTTP transport', () => {
  it('invalidates the actual Budget and Transaction query families after success', async () => {
    primeCache();
    const hook = renderHook(useUpdateTransactionCategoryMutation, { wrapper });
    await act(async () => { await hook.result.current.mutateAsync(variables); });
    expect(patches).toEqual([{ category: '마트 / 편의점' }]);
    expect(client.getQueryState(monthlyBudgetQueryKeys.monthly(2024, 12))?.isInvalidated).toBe(true);
    expect(client.getQueryState(transactionQueryKeys.categories(2024, 12))?.isInvalidated).toBe(true);
  });
  it('does not invalidate or replace confirmed values when the PATCH fails', async () => {
    status = 503; primeCache();
    const hook = renderHook(useUpdateTransactionCategoryMutation, { wrapper });
    await act(async () => { await hook.result.current.mutateAsync(variables).catch(() => {}); });
    expect(patches).toHaveLength(1);
    expect(client.getQueryState(monthlyBudgetQueryKeys.monthly(2024, 12))?.isInvalidated).toBe(false);
    expect(client.getQueryData(monthlyBudgetQueryKeys.monthly(2024, 12))).toEqual(['confirmed-budget']);
  });
  it('rejects an old visit response without invalidating or changing a new visit cache', async () => {
    gate = new Promise<void>(resolve => { release = resolve; }); primeCache();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const hook = renderHook(useUpdateTransactionCategoryMutation, { wrapper });
    let done: Promise<unknown> = Promise.resolve();
    act(() => { done = hook.result.current.mutateAsync(variables).catch(error => error); });
    await waitFor(() => expect(patches).toHaveLength(1));
    const next = new QueryClient(); primeCache(next);
    act(() => useAuthStore.setState({ generation: 81, revision: 2 }));
    await act(async () => { release?.(); await done; });
    expect(invalidate).not.toHaveBeenCalled();
    expect(next.getQueryState(monthlyBudgetQueryKeys.monthly(2024, 12))?.isInvalidated).toBe(false);
    expect(next.getQueryData(monthlyBudgetQueryKeys.monthly(2024, 12))).toEqual(['confirmed-budget']);
    expect(useAuthStore.getState().generation).toBe(81); next.clear();
  });
  it('does not revive authenticated state after logout while a PATCH is in flight', async () => {
    gate = new Promise<void>(resolve => { release = resolve; }); primeCache();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const hook = renderHook(useUpdateTransactionCategoryMutation, { wrapper });
    let done: Promise<unknown> = Promise.resolve();
    act(() => { done = hook.result.current.mutateAsync(variables).catch(error => error); });
    await waitFor(() => expect(patches).toHaveLength(1));
    act(() => useAuthStore.getState().clear());
    await act(async () => { release?.(); await done; });
    expect(invalidate).not.toHaveBeenCalled();
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});

describe('actual App route asset preload cancellation', () => {
  type ImageLoad = { src: string; onload: (() => void) | null; onerror: (() => void) | null };
  let images: ImageLoad[];
  beforeEach(() => {
    useAuthStore.setState({ status: 'anonymous', accessToken: null }); images = [];
    vi.stubGlobal('Image', class {
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      private value = '';
      get src() { return this.value; }
      set src(value: string) { this.value = value; images.push(this); }
    });
  });
  function Navigation() { const navigate = useNavigate(); return <button onClick={() => navigate('/chart')}>test-chart-route</button>; }
  function mountApp() { return render(<QueryClientProvider client={client}><MemoryRouter><Navigation /><App /></MemoryRouter></QueryClientProvider>); }
  it('preloads only the current route and an older route completion cannot hide the new overlay', async () => {
    mountApp();
    await waitFor(() => expect(images.map(image => image.src)).toEqual(scrollLandingAssets));
    fireEvent.click(screen.getByRole('button', { name: 'test-chart-route' }));
    await waitFor(() => expect(images.map(image => image.src)).toEqual([...scrollLandingAssets, ...chartAssets]));
    await act(async () => { images.slice(0, scrollLandingAssets.length).forEach(image => image.onload?.()); });
    expect(screen.getByText('로딩 중...')).toBeInTheDocument();
    await act(async () => { images.slice(scrollLandingAssets.length).forEach(image => image.onload?.()); });
    expect(screen.queryByText('로딩 중...')).not.toBeInTheDocument();
    expect(patches).toHaveLength(0);
  });
  it('settles current asset failures without running a page data request', async () => {
    mountApp();
    await act(async () => { images.forEach(image => image.onerror?.()); });
    expect(screen.queryByText('로딩 중...')).not.toBeInTheDocument();
    expect(patches).toHaveLength(0);
  });
  it('allows an unmounted preload to complete without mounting protected pages', async () => {
    const view = mountApp(); view.unmount();
    await act(async () => { images.forEach(image => image.onload?.()); });
    expect(document.querySelector('[data-demo-page]')).toBeNull();
    expect(patches).toHaveLength(0);
  });
});
