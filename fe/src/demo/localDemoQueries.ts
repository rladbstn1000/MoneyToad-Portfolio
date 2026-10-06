import type { QueryClient } from '@tanstack/react-query';
import { useLocalDemoStore } from './localDemoStore';
import { localAnnualTransactions, localCategoryTransactions, localMonthlyBudgets, localMonthlyTransactions, localYearlyBudgetLeaks } from './localDemoCalculations';

/** Publish every existing derived view from the same state, without emulating an HTTP round trip. */
export function refreshLocalDemoQueries(client: QueryClient, generation: number): void {
  const state = useLocalDemoStore.getState();
  if (state.generation !== generation) return;
  for (const query of client.getQueryCache().getAll()) {
    const [scope, kind, year, month] = query.queryKey;
    if (scope === 'transactions' && kind === 'year') client.setQueryData(query.queryKey, localAnnualTransactions(state.scenario));
    else if (scope === 'monthlyBudgets' && kind === 'yearly') client.setQueryData(query.queryKey, localYearlyBudgetLeaks(state.scenario));
    else if (typeof year === 'number' && typeof month === 'number') {
      if (scope === 'transactions' && kind === 'monthly') client.setQueryData(query.queryKey, localMonthlyTransactions(state.scenario, year, month));
      else if (scope === 'transactions' && kind === 'categories') client.setQueryData(query.queryKey, localCategoryTransactions(state.scenario, year, month));
      else if (scope === 'monthlyBudgets' && kind === 'monthly') client.setQueryData(query.queryKey, localMonthlyBudgets(state.scenario, year, month));
    }
  }
}
