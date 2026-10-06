import type { CategoryTransaction, MonthlyBudgetResponse, MonthlyTransaction, TransactionYear, YearlyBudgetLeakResponse } from '../types';
import { DEMO_BUDGET_CATEGORIES } from './demoBudgetPresentation';
import { localScenarioMonths, localYearMonth } from './localDemoScenario';
import type { LocalScenario } from './localDemoScenario';

export const LOCAL_TRANSACTION_CATEGORIES: readonly string[] = [...DEMO_BUDGET_CATEGORIES, '보험 / 세금'];
const allowedForAnnual = (category: string) => DEMO_BUDGET_CATEGORIES.some(allowed => allowed === category);

export function localMonthlyTransactions(scenario: LocalScenario, year: number, month: number): MonthlyTransaction[] {
  const key = localYearMonth(year, month);
  if (key === null) return [];
  return scenario.transactions.filter(row => row.transactionDateTime.startsWith(`${key}-`))
    .map(row => ({ ...row })).sort((a, b) => b.transactionDateTime.localeCompare(a.transactionDateTime));
}

export function localCategoryTransactions(scenario: LocalScenario, year: number, month: number): CategoryTransaction[] {
  const yearMonth = localYearMonth(year, month);
  const totals = new Map<string, number>();
  for (const row of localMonthlyTransactions(scenario, year, month)) totals.set(row.category, (totals.get(row.category) ?? 0) + row.amount);
  return [...totals].map(([category, totalAmount]) => ({ category, totalAmount,
    leakedAmount: category === '보험 / 세금' ? 0 : Math.max(0, totalAmount - scenario.budgets
      .filter(row => row.yearMonth === yearMonth && row.category === category).reduce((total, row) => total + row.amount, 0)),
  }));
}

export function localMonthlyBudgets(scenario: LocalScenario, year: number, month: number): MonthlyBudgetResponse[] {
  const yearMonth = localYearMonth(year, month);
  if (yearMonth === null || !localScenarioMonths().includes(yearMonth)) return [];
  const spending = new Map(localCategoryTransactions(scenario, year, month).map(row => [row.category, row.totalAmount]));
  return DEMO_BUDGET_CATEGORIES.map(category => {
    const budget = scenario.budgets.find(row => row.yearMonth === yearMonth && row.category === category);
    // Preserve the existing presentation DTO. A missing record is always id:null, never a writable zero budget.
    return { id: budget?.id ?? null, category, budget: budget?.amount ?? 0,
      initialBudget: budget?.initialAmount ?? 0, spending: spending.get(category) ?? 0 };
  });
}

export function localAnnualTransactions(scenario: LocalScenario): TransactionYear[] {
  return localScenarioMonths().map(date => {
    const [year, month] = date.split('-').map(Number);
    const categories = localCategoryTransactions(scenario, year, month).filter(row => allowedForAnnual(row.category));
    return { date, totalAmount: categories.reduce((total, row) => total + row.totalAmount, 0),
      leaked: categories.some(row => row.leakedAmount > 0) };
  });
}

export function localYearlyBudgetLeaks(scenario: LocalScenario): YearlyBudgetLeakResponse[] {
  return localAnnualTransactions(scenario).map(row => ({ budgetDate: row.date, leaked: row.leaked }));
}
