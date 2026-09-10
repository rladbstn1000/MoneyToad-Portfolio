import type { MonthlyTransaction } from '../types';

export type ChartPeriod = { year: number; month: number };

export function isTransactionId(id: unknown): id is number {
  return typeof id === 'number' && Number.isSafeInteger(id) && id > 0;
}

export function belongsToPeriod(date: string, period: ChartPeriod): boolean {
  return date.startsWith(period.year + '-' + String(period.month).padStart(2, '0') + '-');
}

type EditContext = {
  id: unknown;
  category: string;
  period: ChartPeriod;
  activePeriod: ChartPeriod | null;
  transactions: MonthlyTransaction[] | undefined;
  ready: boolean;
  fetching: boolean;
  placeholder: boolean;
  saving: boolean;
  allowedCategories: readonly string[];
};

// Recheck both provenance and query state immediately before calling the mutation.
export function findEditableTransaction(context: EditContext): MonthlyTransaction | undefined {
  const { id, category, period, activePeriod, transactions } = context;
  if (!context.ready || context.fetching || context.placeholder || context.saving
      || !activePeriod || activePeriod.year !== period.year || activePeriod.month !== period.month
      || !Number.isInteger(period.year) || period.year <= 0
      || !Number.isInteger(period.month) || period.month < 1 || period.month > 12
      || !isTransactionId(id) || !context.allowedCategories.includes(category)) return;

  const transaction = transactions?.find(row => row.id === id);
  if (!transaction || transaction.category === category
      || !belongsToPeriod(transaction.transactionDateTime, period)) return;
  return transaction;
}
