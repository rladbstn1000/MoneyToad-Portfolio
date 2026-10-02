import type { MonthlyBudgetResponse } from '../types';

export const DEMO_BUDGET_CATEGORIES = [
  '식비', '카페', '마트 / 편의점', '문화생활', '교통 / 차량', '패션 / 미용',
  '생활용품', '주거 / 통신', '건강 / 병원', '교육', '경조사 / 회비', '기타',
] as const;

export function parseDemoBudgetMonth(value: string | undefined): number | null {
  return value && /^(?:[1-9]|1[0-2])$/.test(value) ? Number(value) : null;
}

/** Presentation only: missing budget IDs stay read-only; no synthetic writable IDs. */
export function adaptDemoBudgets(value: unknown): MonthlyBudgetResponse[] | null {
  if (!Array.isArray(value)) return null;
  if (value.length === 0) return [];
  if (value.length !== DEMO_BUDGET_CATEGORIES.length) return null;
  const categories = new Set<string>();
  const ids = new Set<number>();
  const result: MonthlyBudgetResponse[] = [];
  for (const row of value) {
    if (!row || typeof row !== 'object' || typeof row.category !== 'string'
      || !DEMO_BUDGET_CATEGORIES.some(category => category === row.category)
      || categories.has(row.category)
      || ![row.budget, row.spending, row.initialBudget].every(amount => Number.isSafeInteger(amount) && amount >= 0)
      || row.id !== null && (!Number.isSafeInteger(row.id) || row.id <= 0 || ids.has(row.id))) return null;
    categories.add(row.category);
    if (row.id !== null) ids.add(row.id);
    result.push({ id: row.id, budget: row.budget, spending: row.spending,
      category: row.category, initialBudget: row.initialBudget });
  }
  return result;
}
