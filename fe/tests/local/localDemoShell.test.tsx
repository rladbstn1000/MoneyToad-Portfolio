import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../../src/App';
import AuthProvider from '../../src/auth/AuthProvider';
import { parseAuthMode, parseDemoDataMode, isLocalDemo } from '../../src/auth/authMode';
import RouteGuard from '../../src/components/RouteGuard';
import DemoAuthStatus from '../../src/components/DemoAuthStatus';
import DemoHeader from '../../src/components/DemoHeader';
import DemoExperienceProvider from '../../src/demo/DemoExperienceProvider';
import { useDemoExperience } from '../../src/demo/useDemoExperience';
import { useLocalDemoStore } from '../../src/demo/localDemoStore';
import { getLocalDemoQueryClient } from '../../src/demo/localDemoQueryClient';
import { useAuthStore } from '../../src/store/authStore';
import { request, aiRequest, axiosInstance } from '../../src/api/client';
import { demoHttp } from '../../src/api/services/demoAuth';

// jsdom has no canvas renderer; real Lottie remains covered by Chromium.
vi.mock('lottie-react', () => ({ default: () => null }));

beforeEach(() => { useLocalDemoStore.getState().end(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function Place() { return <p data-testid="path">{useLocation().pathname}</p>; }
function Shell({ entry = '/' }: { entry?: string }) {
  return <StrictMode><MemoryRouter initialEntries={[entry]}><AuthProvider><Routes>
    <Route path="/" element={<DemoAuthStatus landing />} />
    <Route path="*" element={<RouteGuard><DemoHeader /><Place /></RouteGuard>} />
  </Routes></AuthProvider></MemoryRouter></StrictMode>;
}
function changeBudget() {
  const state = useLocalDemoStore.getState();
  const budget = state.scenario.budgets.find(row => row.yearMonth === '2026-10' && row.category === '카페');
  if (!budget) throw new Error('Authored budget missing');
  act(() => state.updateBudget({ id: budget.id, amount: 60000, year: 2026, month: 10, generation: state.generation }));
  return budget.id;
}

describe('explicit local experience mode', () => {
  it('defaults omitted data mode to the existing remote contract', () => {
    expect(parseDemoDataMode(undefined, 'demo')).toBe('remote');
    expect(parseDemoDataMode(undefined, parseAuthMode(undefined))).toBe('remote');
  });
  it('accepts explicit demo/local without a backend setting', () => {
    expect(isLocalDemo).toBe(true);
    expect(import.meta.env.VITE_BACK_URL).toBeUndefined();
    expect(parseDemoDataMode('local', 'demo')).toBe('local');
    expect(parseDemoDataMode('remote', 'oauth')).toBe('remote');
  });
  it.each(['', ' ', ' LOCAL', 'LOCAL', 'local ', 'other', null, false])('rejects invalid data mode %s', value => {
    expect(() => parseDemoDataMode(value, 'demo')).toThrow('VITE_DEMO_DATA_MODE');
  });
  it('rejects OAuth plus local', () => expect(() => parseDemoDataMode('local', 'oauth')).toThrow('requires'));
});

describe('local entry and visit lifetime', () => {
  it('renders landing without waiting for global image preloads', () => {
    const preload = vi.spyOn(globalThis, 'Image').mockImplementation(() => { throw new Error('Global preload is not a local dependency'); });
    const xhr = vi.spyOn(XMLHttpRequest.prototype, 'send');
    const { container } = render(<MemoryRouter><AuthProvider><App /></AuthProvider></MemoryRouter>);
    expect(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })).toBeVisible();
    expect(container.querySelector('.loading-overlay')).toBeNull();
    expect(preload).not.toHaveBeenCalled(); expect(xhr).not.toHaveBeenCalled();
  });
  it('renders direct routes without server auth or storage changes', () => {
    const xhr = vi.spyOn(XMLHttpRequest.prototype, 'send');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const originalAuth = useAuthStore.getState();
    render(<Shell entry="/mypage" />);
    expect(screen.getByTestId('path')).toHaveTextContent('/mypage');
    expect(screen.queryByText(/서버 준비|인증 세션|서버 다시 확인/)).not.toBeInTheDocument();
    expect(xhr).not.toHaveBeenCalled(); expect(fetchSpy).not.toHaveBeenCalled();
    expect(storage).not.toHaveBeenCalled(); expect(useAuthStore.getState()).toBe(originalAuth);
  });
  it('offers the same memory notice on direct routes without remote messages', () => {
    render(<Shell entry="/mypage" />);
    const summary = screen.getByText('샘플 체험 안내');
    expect(summary.tagName).toBe('SUMMARY');
    expect(summary.closest('details')).toHaveClass('local-demo-notice');
    fireEvent.click(summary);
    expect(summary.closest('details')).toHaveAttribute('open');
    expect(screen.getByText('샘플 데이터로 체험합니다. 변경 내용은 이 탭에서만 유지되며 새로고침하면 초기화됩니다.')).toBeVisible();
    expect(screen.queryByText(/서버 준비|서버 다시 확인|로그인 대기|인증 세션 만료/)).not.toBeInTheDocument();
  });
  it('enters explicitly and continues the same memory visit', () => {
    render(<Shell />);
    fireEvent.click(screen.getByRole('button', { name: '샘플 데이터로 체험하기' }));
    expect(screen.getByTestId('path')).toHaveTextContent('/pot');
    const budgetId = changeBudget();
    fireEvent.click(screen.getByRole('link', { name: '마당' }));
    fireEvent.click(screen.getByRole('button', { name: '체험 이어가기' }));
    expect(useLocalDemoStore.getState().scenario.budgets.find(row => row.id === budgetId)?.amount).toBe(60000);
  });
  it('preserves edits during StrictMode rerender, visibility and ordinary pageshow', () => {
    const view = render(<Shell entry="/chart" />);
    const budgetId = changeBudget(); const generation = useLocalDemoStore.getState().generation;
    view.rerender(<Shell entry="/chart" />);
    fireEvent(document, new Event('visibilitychange'));
    fireEvent(window, new PageTransitionEvent('pageshow', { persisted: false }));
    expect(useLocalDemoStore.getState().generation).toBe(generation);
    expect(useLocalDemoStore.getState().scenario.budgets.find(row => row.id === budgetId)?.amount).toBe(60000);
  });
  it('resets on persisted pageshow and clears the old query cache', () => {
    render(<Shell entry="/chart" />);
    const budgetId = changeBudget(); const client = getLocalDemoQueryClient();
    client.setQueryData(['old-view'], { sample: true });
    fireEvent(window, new PageTransitionEvent('pageshow', { persisted: true }));
    expect(client.getQueryCache().getAll()).toHaveLength(0);
    expect(getLocalDemoQueryClient()).not.toBe(client);
    expect(useLocalDemoStore.getState().scenario.budgets.find(row => row.id === budgetId)?.amount).toBe(40000);
    expect(screen.getByTestId('path')).toHaveTextContent('/chart');
  });
  it('reset preserves route and end returns home without server logout', () => {
    const xhr = vi.spyOn(XMLHttpRequest.prototype, 'send');
    render(<Shell entry="/chart" />);
    const budgetId = changeBudget();
    fireEvent.click(screen.getByRole('button', { name: '처음부터 다시하기' }));
    expect(screen.getByTestId('path')).toHaveTextContent('/chart');
    expect(useLocalDemoStore.getState().scenario.budgets.find(row => row.id === budgetId)?.amount).toBe(40000);
    fireEvent.click(screen.getByRole('button', { name: '체험 종료' }));
    expect(screen.getByRole('button', { name: '샘플 데이터로 체험하기' })).toBeVisible();
    expect(useLocalDemoStore.getState().hasStarted).toBe(false); expect(xhr).not.toHaveBeenCalled();
  });
  it('rejects accidental server calls before any HTTP', async () => {
    const xhr = vi.spyOn(XMLHttpRequest.prototype, 'send');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const results = await Promise.allSettled([request({ url: '/api/transactions' }), aiRequest({ url: '/api/analysis' }),
      axiosInstance.get('/api/transactions'), demoHttp.ready(new AbortController().signal, 1),
      demoHttp.token('login'), demoHttp.token('reissue'), demoHttp.session(''), demoHttp.logout('')]);
    expect(results.every(result => result.status === 'rejected')).toBe(true);
    expect(xhr).not.toHaveBeenCalled(); expect(fetchSpy).not.toHaveBeenCalled();
  });
  it('resets profile memory and rejects an old callback', () => {
    let oldUpdate: ReturnType<typeof useDemoExperience>['updateProfile'] | undefined;
    function Profile() {
      const { profile, updateProfile } = useDemoExperience(); oldUpdate ??= updateProfile;
      return <button onClick={() => updateProfile({ cardPreset: 'B' })}>{profile.cardPreset}</button>;
    }
    function Visit() {
      const generation = useLocalDemoStore(state => state.generation);
      return <DemoExperienceProvider key={generation}><Profile /></DemoExperienceProvider>;
    }
    render(<Visit />);
    fireEvent.click(screen.getByRole('button', { name: 'A' }));
    expect(screen.getByRole('button', { name: 'B' })).toBeVisible();
    act(() => useLocalDemoStore.getState().reset()); act(() => oldUpdate?.({ cardPreset: 'B' }));
    expect(screen.getByRole('button', { name: 'A' })).toBeVisible();
  });
});
