import { StrictMode } from 'react';
import type { PropsWithChildren } from 'react';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMonthlyTransactionsQuery, useYearTransactionQuery, useCategoryTransactionsQuery, usePeerYearTransactionQuery } from '../../src/api/queries/transactionQuery';
import { useMonthlyBudgetsQuery, useYearlyBudgetLeaksQuery } from '../../src/api/queries/budgetQuery';
import { useDemoBudgetChanges } from '../../src/demo/useDemoBudgetChanges';
import { useUpdateTransactionCategoryMutation } from '../../src/api/mutation/transactionMutation';
import { useLocalDemoStore } from '../../src/demo/localDemoStore';
import { LOCAL_PRACTICE_MERCHANT } from '../../src/demo/localDemoScenario';
import { refreshLocalDemoQueries } from '../../src/demo/localDemoQueries';
import { monthlyBudgetQueryKeys, transactionQueryKeys } from '../../src/api/queryKeys';
import * as transactions from '../../src/api/services/transactions';
import * as budgets from '../../src/api/services/budgets';

let client: QueryClient;
beforeEach(() => {
  useLocalDemoStore.getState().end();
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  for (const name of ['getYearTransaction', 'getPeerYearTransaction', 'getMonthlyTransactions', 'getCategoryTransactions', 'updateTransactionCategory'] as const) vi.spyOn(transactions, name).mockRejectedValue(new Error('Local mode must not use HTTP'));
  for (const name of ['getMonthlyBudgets', 'getYearlyBudgetLeaks', 'updateBudget'] as const) vi.spyOn(budgets, name).mockRejectedValue(new Error('Local mode must not use HTTP'));
});
afterEach(async () => { cleanup(); await client.cancelQueries(); client.clear(); vi.restoreAllMocks(); });
const wrapper = ({ children }: PropsWithChildren) => <StrictMode><QueryClientProvider client={client}>{children}</QueryClientProvider></StrictMode>;
function useViews() {
  return { annual: useYearTransactionQuery(), monthly: useMonthlyTransactionsQuery(2026, 10), categories: useCategoryTransactionsQuery(2026, 10), budgets: useMonthlyBudgetsQuery(2026, 10), budgetYear: useYearlyBudgetLeaksQuery(), peer: usePeerYearTransactionQuery() };
}
function expectNoServices() {
  for (const value of Object.values(transactions)) expect(value).not.toHaveBeenCalled();
  for (const value of Object.values(budgets)) expect(value).not.toHaveBeenCalled();
}

describe('local hooks with memory-only reads and edits', () => {
  it('renders all current views synchronously without authentication or HTTP', () => {
    const { result } = renderHook(useViews, { wrapper });
    expect(result.current.annual.isSuccess).toBe(true); expect(result.current.annual.data).toHaveLength(12);
    expect(result.current.monthly.isSuccess).toBe(true); expect(result.current.monthly.data).toHaveLength(20);
    expect(result.current.budgets.data).toHaveLength(12); expect(result.current.budgetYear.data).toHaveLength(12);
    expect(result.current.peer.fetchStatus).toBe('idle'); expect(result.current.peer.data).toBeUndefined(); expectNoServices();
  });
  it('changes budget immediately without 500ms timer and publishes every cached aggregate', async () => {
    const timers = vi.spyOn(window, 'setTimeout');
    const { result, rerender } = renderHook(() => ({ views: useViews(), change: useDemoBudgetChanges(2026, 10, useLocalDemoStore(state => state.generation)) }), { wrapper });
    const id = result.current.views.budgets.data?.find(row => row.category === '카페')?.id;
    if (id === null || id === undefined) throw new Error('Missing fixture budget');
    act(() => result.current.change.change(id, 60000));
    expect(useLocalDemoStore.getState().scenario.budgets.find(row => row.id === id)?.amount).toBe(60000);
    expect(client.getQueryData(monthlyBudgetQueryKeys.monthly(2026, 10))).toEqual(expect.arrayContaining([expect.objectContaining({ id, budget: 60000 })]));
    await waitFor(() => expect(result.current.views.annual.data?.at(-1)?.leaked).toBe(false));
    expect(result.current.views.categories.data?.find(row => row.category === '카페')?.leakedAmount).toBe(0);
    expect(result.current.change.pending).toEqual({}); expect(result.current.change.saving.size).toBe(0);
    expect(timers.mock.calls.filter(([, delay]) => delay === 500)).toHaveLength(0);
    rerender(); expect(result.current.views.budgets.data?.find(row => row.id === id)?.budget).toBe(60000); expectNoServices();
  });
  it('reclassifies a current response transaction and publishes budgets and annual data', async () => {
    const { result } = renderHook(() => ({ views: useViews(), mutation: useUpdateTransactionCategoryMutation() }), { wrapper });
    const transaction = result.current.views.monthly.data?.find(row => row.merchantName === LOCAL_PRACTICE_MERCHANT);
    if (!transaction) throw new Error('Missing fixture transaction');
    await act(() => result.current.mutation.mutateAsync({ transactionId: transaction.id, data: { category: '마트 / 편의점' }, period: { year: 2026, month: 10 } }));
    await waitFor(() => expect(result.current.views.monthly.data?.find(row => row.id === transaction.id)?.category).toBe('마트 / 편의점'));
    expect(result.current.views.annual.data?.at(-1)).toMatchObject({ totalAmount: 908000, leaked: false });
    expect(result.current.views.budgets.data?.find(row => row.category === '카페')?.spending).toBe(28000);
    expect(result.current.views.categories.data?.find(row => row.category === '마트 / 편의점')?.totalAmount).toBe(120000); expectNoServices();
  });
  it('rejects transaction edit absent from current response', async () => {
    const { result } = renderHook(() => ({ views: useViews(), mutation: useUpdateTransactionCategoryMutation() }), { wrapper });
    const transaction = result.current.views.monthly.data?.find(row => row.merchantName === LOCAL_PRACTICE_MERCHANT);
    if (!transaction) throw new Error('Missing fixture transaction');
    client.setQueryData(transactionQueryKeys.monthly(2026, 10), []);
    await act(async () => { await expect(result.current.mutation.mutateAsync({ transactionId: transaction.id, data: { category: '마트 / 편의점' }, period: { year: 2026, month: 10 } })).rejects.toThrow('현재 샘플 거래'); });
    expect(useLocalDemoStore.getState().scenario.transactions.find(row => row.id === transaction.id)?.category).toBe('카페'); expectNoServices();
  });
  it('blocks retained budget callback and old cache publication after reset', () => {
    const { result } = renderHook(() => ({ views: useViews(), change: useDemoBudgetChanges(2026, 10, useLocalDemoStore(state => state.generation)) }), { wrapper });
    const id = result.current.views.budgets.data?.find(row => row.category === '카페')?.id;
    if (id === null || id === undefined) throw new Error('Missing fixture budget');
    const stale = result.current.change.change; const generation = useLocalDemoStore.getState().generation;
    act(() => useLocalDemoStore.getState().reset());
    const querySnapshot = client.getQueryData(monthlyBudgetQueryKeys.monthly(2026, 10));
    act(() => { stale(id, 60000); refreshLocalDemoQueries(client, generation); });
    expect(useLocalDemoStore.getState().scenario.budgets.find(row => row.id === id)?.amount).toBe(40000);
    expect(client.getQueryData(monthlyBudgetQueryKeys.monthly(2026, 10))).toBe(querySnapshot); expectNoServices();
  });
  it('blocks category operation queued immediately before reset', async () => {
    const { result } = renderHook(() => ({ views: useViews(), mutation: useUpdateTransactionCategoryMutation() }), { wrapper });
    const transaction = result.current.views.monthly.data?.find(row => row.merchantName === LOCAL_PRACTICE_MERCHANT);
    if (!transaction) throw new Error('Missing fixture transaction');
    let operation: Promise<unknown> | undefined;
    act(() => {
      operation = result.current.mutation.mutateAsync({ transactionId: transaction.id, data: { category: '마트 / 편의점' }, period: { year: 2026, month: 10 } });
      useLocalDemoStore.getState().reset();
    });
    await act(async () => { await expect(operation).rejects.toThrow('현재 샘플 거래'); });
    expect(useLocalDemoStore.getState().scenario.transactions.find(row => row.id === transaction.id)?.category).toBe('카페'); expectNoServices();
  });
});
