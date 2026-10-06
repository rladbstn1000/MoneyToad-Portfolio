import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockInstance } from 'vitest';
import ToadAdvice from '../../src/pages/ToadAdvice';
import { useLocalDemoStore } from '../../src/demo/localDemoStore';
import { refreshLocalDemoQueries } from '../../src/demo/localDemoQueries';

let client: QueryClient;
let requests: MockInstance<typeof XMLHttpRequest.prototype.send>;
let fetches: MockInstance<typeof fetch>;
beforeEach(() => {
  useLocalDemoStore.getState().end();
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  requests = vi.spyOn(XMLHttpRequest.prototype, 'send');
  fetches = vi.spyOn(globalThis, 'fetch');
});
afterEach(async () => {
  cleanup(); await client.cancelQueries(); client.clear();
  expect(requests).not.toHaveBeenCalled(); expect(fetches).not.toHaveBeenCalled();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
function Location() { const location = useLocation(); return <output data-testid="location">{location.pathname}{location.hash}</output>; }
function mount() {
  return render(<StrictMode><QueryClientProvider client={client}><MemoryRouter initialEntries={['/toadAdvice']}>
    <ToadAdvice /><Location />
  </MemoryRouter></QueryClientProvider></StrictMode>);
}
const detailButton = () => screen.getByRole('button', { name: '카테고리별 소비 조언 보기 ↓' });

describe('local advice in-page navigation', () => {
  it('keeps the overview and already-mounted detail cards in separate consecutive sections', () => {
    mount();
    const overview = screen.getByRole('region', { name: '두꺼비의 소비내역 조언소' });
    const details = screen.getByRole('region', { name: '10월 과소비 요약' });
    const card = screen.getByRole('button', { name: /카페.*냥|냥.*카페/ });
    expect(overview.nextElementSibling).toBe(details);
    expect(overview.lastElementChild).toBe(detailButton().parentElement);
    expect(overview).toContainElement(screen.getByRole('button', { name: '누수 10월' }));
    expect(overview).not.toContainElement(card);
    expect(details).toContainElement(card);
    expect(card.closest('[hidden]')).toBeNull();
    expect(details).not.toHaveAttribute('hidden');
    fireEvent.click(detailButton());
    expect(screen.getByRole('button', { name: /카페.*냥|냥.*카페/ })).toBe(card);
  });
  it('takes focus to the actual result without changing location, scenario or making HTTP requests', () => {
    mount();
    const scenario = useLocalDemoStore.getState().scenario;
    const heading = screen.getByRole('heading', { name: '10월 과소비 요약' });
    const scroll = vi.spyOn(heading, 'scrollIntoView');
    expect(detailButton()).toHaveAccessibleDescription('아래에서 항목별 소비와 조언을 확인할 수 있어요.');
    expect(detailButton()).toHaveAttribute('aria-controls', heading.closest('section')?.id);
    expect(screen.queryByText('아래로 스크롤')).not.toBeInTheDocument();
    fireEvent.click(detailButton());
    expect(heading).toHaveFocus();
    expect(scroll).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/toadAdvice$/);
    expect(useLocalDemoStore.getState().scenario).toBe(scenario);
  });
  it.each(['{Enter}', ' '])('supports native button activation with %s', async key => {
    mount(); const user = userEvent.setup();
    detailButton().focus(); await user.keyboard(key);
    expect(screen.getByRole('heading', { name: '10월 과소비 요약' })).toHaveFocus();
  });
  it('honors reduced motion when the navigation is activated', () => {
    mount();
    const heading = screen.getByRole('heading', { name: '10월 과소비 요약' });
    const scroll = vi.spyOn(heading, 'scrollIntoView');
    const media = vi.fn().mockReturnValue({ matches: true });
    vi.stubGlobal('matchMedia', media);
    fireEvent.click(detailButton());
    expect(media).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    expect(scroll).toHaveBeenCalledWith({ behavior: 'auto', block: 'start' });
    expect(heading).toHaveFocus();
  });
  it('changes the invitation to an actual empty result after the current overage is removed', async () => {
    mount();
    const state = useLocalDemoStore.getState();
    const budget = state.scenario.budgets.find(row => row.yearMonth === '2026-10' && row.category === '카페');
    if (!budget) throw new Error('Authored cafe budget missing');
    act(() => {
      state.updateBudget({ id: budget.id, amount: 60000, year: 2026, month: 10, generation: state.generation });
      refreshLocalDemoQueries(client, state.generation);
    });
    await screen.findByRole('button', { name: '이번 달 소비 결과 보기 ↓' });
    expect(screen.queryByRole('button', { name: '카테고리별 소비 조언 보기 ↓' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '카페' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '이번 달 소비 결과 보기 ↓' }));
    expect(screen.getByRole('heading', { name: '10월 소비 확인 결과' })).toHaveFocus();
    expect(screen.getByText('이달에는 분석할 과소비 항목이 없어요. 다른 달의 소비도 확인해 보세요.')).toBeInTheDocument();
  });
  it('keeps month selection, modal Escape and return focus after detail navigation', () => {
    mount();
    fireEvent.click(screen.getByRole('button', { name: '누수 5월' }));
    fireEvent.click(detailButton());
    expect(screen.getByRole('heading', { name: '5월 과소비 요약' })).toHaveFocus();
    const card = screen.getByRole('button', { name: /문화생활/ });
    fireEvent.click(card);
    expect(screen.getByRole('dialog', { name: '문화생활' })).toHaveTextContent('120,000냥');
    expect(screen.getByRole('button', { name: '조언 상세 닫기' })).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(card).toHaveFocus();
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/toadAdvice$/);
  });
});
