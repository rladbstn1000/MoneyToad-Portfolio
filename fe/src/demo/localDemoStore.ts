import { create } from 'zustand';
import { createLocalDemoScenario, localYearMonth } from './localDemoScenario';
import type { LocalScenario } from './localDemoScenario';
import { LOCAL_TRANSACTION_CATEGORIES } from './localDemoCalculations';

export type LocalBudgetChange = { id: number; amount: number; year: number; month: number; generation: number };
export type LocalCategoryChange = { id: number; category: string; year: number; month: number; generation: number };
export type LocalDemoState = {
  scenario: LocalScenario;
  generation: number;
  revision: number;
  hasStarted: boolean;
  start: () => void;
  reset: () => void;
  end: () => void;
  updateBudget: (change: LocalBudgetChange) => boolean;
  updateCategory: (change: LocalCategoryChange) => boolean;
};
const positiveId = (id: number) => Number.isSafeInteger(id) && id > 0;

/** Browser-memory experience only: no authentication, storage middleware, HTTP or clock dependency. */
export const useLocalDemoStore = create<LocalDemoState>()((set, get) => ({
  scenario: createLocalDemoScenario(), generation: 0, revision: 0, hasStarted: false,
  start: () => {
    if (!get().hasStarted) set({ hasStarted: true });
  },
  reset: () => set(state => ({ scenario: createLocalDemoScenario(), generation: state.generation + 1, revision: 0, hasStarted: true })),
  end: () => set(state => ({ scenario: createLocalDemoScenario(), generation: state.generation + 1, revision: 0, hasStarted: false })),
  updateBudget: ({ id, amount, year, month, generation }) => {
    const state = get();
    const yearMonth = localYearMonth(year, month);
    if (generation !== state.generation || !positiveId(id) || !Number.isSafeInteger(amount) || amount < 0
      || !state.scenario.budgets.some(row => row.id === id && row.yearMonth === yearMonth)) return false;
    set({ revision: state.revision + 1, scenario: Object.freeze({ ...state.scenario,
      budgets: Object.freeze(state.scenario.budgets.map(row => row.id === id ? Object.freeze({ ...row, amount }) : row)) }) });
    return true;
  },
  updateCategory: ({ id, category, year, month, generation }) => {
    const state = get();
    const yearMonth = localYearMonth(year, month);
    if (generation !== state.generation || !positiveId(id) || yearMonth === null || !LOCAL_TRANSACTION_CATEGORIES.includes(category)
      || !state.scenario.transactions.some(row => row.id === id && row.transactionDateTime.startsWith(`${yearMonth}-`))) return false;
    set({ revision: state.revision + 1, scenario: Object.freeze({ ...state.scenario,
      transactions: Object.freeze(state.scenario.transactions.map(row => row.id === id ? Object.freeze({ ...row, category }) : row)) }) });
    return true;
  },
}));
