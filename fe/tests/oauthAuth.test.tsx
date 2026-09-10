import { StrictMode } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import AuthProvider from '../src/auth/AuthProvider';
import AuthCallback from '../src/pages/AuthCallback';
import ScrollLandingPage from '../src/pages/ScrollLandingPage';
import { useAuthStore } from '../src/store/authStore';
import { logout, reissueToken } from '../src/api/services/auth';
import { bootstrapDemo } from '../src/auth/demoCoordinator';

const ORIGIN = 'http://127.0.0.1:18080';
let demoRequests = 0;
let cardRequests = 0;
let authRequests: string[] = [];
let unexpected = 0;
let hasCard = true;
const server = setupServer(
  http.get(`${ORIGIN}/api/cards`, () => { cardRequests++; return HttpResponse.json(hasCard ? { id: 1 } : null); }),
  http.post(`${ORIGIN}/api/auth/reissue`, () => { authRequests.push('reissue'); return HttpResponse.json({ accessToken: 'oauth-synthetic-new' }); }),
  http.post(`${ORIGIN}/api/auth/logout`, () => { authRequests.push('logout'); return new HttpResponse(null, { status: 204 }); }),
  http.all(`${ORIGIN}/api/auth/demo/*`, () => { demoRequests++; return new HttpResponse(null, { status: 500 }); }),
);
beforeAll(() => server.listen({ onUnhandledRequest() { unexpected++; throw new Error('Unexpected request'); } }));
afterAll(() => server.close());
beforeEach(() => { demoRequests = 0; cardRequests = 0; authRequests = []; unexpected = 0; hasCard = true; localStorage.clear(); useAuthStore.getState().clear(); });
afterEach(() => { cleanup(); expect(demoRequests).toBe(0); expect(unexpected).toBe(0); });

describe('original OAuth regression', () => {
  it('retains the existing persist key, version, token payload and hydration', async () => {
    useAuthStore.getState().setAccessToken('oauth-synthetic');
    const persisted = JSON.parse(localStorage.getItem('accessToken')!);
    expect(Object.keys(persisted.state)).toEqual(['accessToken']); expect(persisted.version).toBe(1);
    expect(persisted.state.accessToken === useAuthStore.getState().accessToken).toBe(true);
    useAuthStore.setState({ accessToken: null });
    localStorage.setItem('accessToken', JSON.stringify(persisted));
    vi.resetModules();
    const { useAuthStore: reloaded } = await import('../src/store/authStore');
    expect(reloaded.getState().accessToken === persisted.state.accessToken).toBe(true);
    await bootstrapDemo();
  });
  it.each([true, false])('keeps callback storage and card routing (card=%s)', async card => {
    hasCard = card;
    // A fresh provider's query client is intentionally not used to hide the original callback behavior.
    const { QueryClient, QueryClientProvider } = await import('@tanstack/react-query');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<StrictMode><MemoryRouter initialEntries={['/auth/callback?accessToken=oauth-synthetic-callback']}>
      <QueryClientProvider client={client}><Routes>
        <Route path="/auth/callback" element={<AuthCallback />} />
        <Route path="/pot/:month" element={<p>original pot destination</p>} />
        <Route path="/userInfo" element={<p>original user destination</p>} />
      </Routes></QueryClientProvider></MemoryRouter></StrictMode>);
    await screen.findByText(card ? 'original pot destination' : 'original user destination');
    expect(cardRequests).toBe(1);
    expect(useAuthStore.getState().accessToken === 'oauth-synthetic-callback').toBe(true);
    expect(JSON.parse(localStorage.getItem('accessToken')!).state.accessToken === useAuthStore.getState().accessToken).toBe(true);
    client.clear();
  });
  it('does not restore demo in the OAuth provider and retains the existing landing destination', async () => {
    useAuthStore.getState().setAccessToken('oauth-synthetic');
    render(<StrictMode><MemoryRouter><AuthProvider><Routes>
      <Route path="/" element={<ScrollLandingPage />} />
      <Route path="/pot/:month" element={<p>original landing destination</p>} />
    </Routes></AuthProvider></MemoryRouter></StrictMode>);
    fireEvent.click(screen.getByRole('button', { name: '장독대로 가기' }));
    await screen.findByText('original landing destination');
    expect(authRequests).toEqual([]);
  });
  it('keeps general reissue/logout on their original endpoints', async () => {
    await reissueToken(); await logout();
    await waitFor(() => expect(authRequests).toEqual(['reissue', 'logout']));
  });
  it('retains the OAuth login control without mounting demo controls', () => {
    localStorage.clear();
    render(<MemoryRouter><AuthProvider><ScrollLandingPage /></AuthProvider></MemoryRouter>);
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '샘플 데이터로 체험하기' })).not.toBeInTheDocument();
  });
});
