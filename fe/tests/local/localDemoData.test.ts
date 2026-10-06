import { beforeEach, describe, expect, it } from 'vitest';
import { createLocalDemoScenario, LOCAL_PRACTICE_MERCHANT, localScenarioMonths } from '../../src/demo/localDemoScenario';
import { localAnnualTransactions, localCategoryTransactions, localMonthlyBudgets, localMonthlyTransactions } from '../../src/demo/localDemoCalculations';
import { useLocalDemoStore } from '../../src/demo/localDemoStore';
import { buildDemoAdvice } from '../../src/demo/demoAdvice';

const store = () => useLocalDemoStore.getState();
const cafeBudget = () => store().scenario.budgets.find(row => row.yearMonth === '2026-10' && row.category === '카페')!;
const practice = () => store().scenario.transactions.find(row => row.merchantName === LOCAL_PRACTICE_MERCHANT)!;
const current = () => localCategoryTransactions(store().scenario, 2026, 10);
const leak = () => current().reduce((sum, row) => sum + row.leakedAmount, 0);
beforeEach(() => store().end());

describe('authored local V1 data and projections', () => {
  it('creates exactly twelve fixed months, 240 transactions, 72 actual budgets and the full aggregate', () => {
    const scenario = createLocalDemoScenario();
    expect(scenario.metadata.anchorYearMonth).toBe('2026-10');
    expect(scenario.transactions).toHaveLength(240); expect(scenario.budgets).toHaveLength(72);
    expect(localScenarioMonths()).toHaveLength(12);
    expect(localScenarioMonths()[0]).toBe('2025-11'); expect(localScenarioMonths().at(-1)).toBe('2026-10');
    expect(localAnnualTransactions(scenario).reduce((sum, row) => sum + row.totalAmount, 0)).toBe(9990000);
    for (const date of localScenarioMonths()) {
      expect(scenario.transactions.filter(row => row.transactionDateTime.startsWith(date))).toHaveLength(20);
      expect(scenario.budgets.filter(row => row.yearMonth === date)).toHaveLength(6);
    }
    expect(scenario.transactions.every(row => row.transactionDateTime <= `${scenario.metadata.anchorDate}T00:00:00`)).toBe(true);
  });
  it('is deterministic, independently owned and frozen down to each record', () => {
    const first = createLocalDemoScenario(); const second = createLocalDemoScenario();
    expect(first).toEqual(second); expect(first).not.toBe(second);
    expect(first.transactions[0]).not.toBe(second.transactions[0]);
    expect(Object.isFrozen(first)).toBe(true); expect(Object.isFrozen(first.transactions)).toBe(true);
    expect(first.transactions.every(Object.isFrozen)).toBe(true); expect(first.budgets.every(Object.isFrozen)).toBe(true);
  });
  it('derives baseline and historical practice values instead of embedding view totals', () => {
    expect(current().reduce((sum, row) => sum + row.totalAmount, 0)).toBe(908000);
    expect(current().find(row => row.category === '카페')).toEqual({ category: '카페', totalAmount: 58000, leakedAmount: 18000 });
    const historical = localMonthlyBudgets(store().scenario, 2026, 5).find(row => row.category === '문화생활')!;
    expect(historical.spending).toBe(180000); expect(historical.budget).toBe(60000);
    expect(localCategoryTransactions(store().scenario, 2026, 5).reduce((sum, row) => sum + row.leakedAmount, 0)).toBe(120000);
    expect(localAnnualTransactions(store().scenario).find(row => row.date === '2026-05')?.totalAmount).toBe(962000);
  });
  it('distinguishes missing budgets from writable zero budgets', () => {
    const id = cafeBudget().id;
    expect(store().updateBudget({ id, amount: 0, year: 2026, month: 10, generation: store().generation })).toBe(true);
    const rows = localMonthlyBudgets(store().scenario, 2026, 10);
    expect(rows).toHaveLength(12); expect(rows.filter(row => row.id === null)).toHaveLength(6);
    expect(rows.find(row => row.category === '카페')).toMatchObject({ id, budget: 0, spending: 58000, initialBudget: 40000 });
    expect(rows.find(row => row.category === '교육')).toMatchObject({ id: null, spending: 0 });
  });
  it('updates budget immediately in every aggregate without mutating initial values', () => {
    const initial = store().scenario;
    expect(store().updateBudget({ id: cafeBudget().id, amount: 60000, year: 2026, month: 10, generation: store().generation })).toBe(true);
    expect(leak()).toBe(0); expect(current().reduce((sum, row) => sum + row.totalAmount, 0)).toBe(908000);
    expect(localAnnualTransactions(store().scenario).at(-1)?.leaked).toBe(false);
    expect(buildDemoAdvice(localMonthlyBudgets(store().scenario, 2026, 10), localMonthlyTransactions(store().scenario, 2026, 10)).advices).toHaveLength(0);
    expect(initial.budgets.find(row => row.id === cafeBudget().id)?.amount).toBe(40000);
    store().updateBudget({ id: cafeBudget().id, amount: 40000, year: 2026, month: 10, generation: store().generation });
    expect(leak()).toBe(18000);
  });
  it('reclassifies the actual practice transaction and removes stale cafe advice', () => {
    const initial = store().scenario;
    expect(store().updateCategory({ id: practice().id, category: '마트 / 편의점', year: 2026, month: 10, generation: store().generation })).toBe(true);
    expect(current().find(row => row.category === '카페')?.totalAmount).toBe(28000);
    expect(current().find(row => row.category === '마트 / 편의점')?.totalAmount).toBe(120000);
    expect(current().reduce((sum, row) => sum + row.totalAmount, 0)).toBe(908000); expect(leak()).toBe(0);
    expect(localAnnualTransactions(store().scenario).at(-1)?.leaked).toBe(false);
    expect(buildDemoAdvice(localMonthlyBudgets(store().scenario, 2026, 10), localMonthlyTransactions(store().scenario, 2026, 10)).advices).toHaveLength(0);
    expect(initial.transactions.find(row => row.id === practice().id)?.category).toBe('카페');
  });
  it('keeps merchant frequency tied to the exact merchant, not a category label', () => {
    const output = buildDemoAdvice(localMonthlyBudgets(store().scenario, 2026, 10), localMonthlyTransactions(store().scenario, 2026, 10));
    expect(output.details['카페'].mostFrequent.count).toBe(1);
    expect(output.details['카페'].mostFrequent.merchant).toBe(LOCAL_PRACTICE_MERCHANT);
    expect(output.details['카페'].mostFrequent.totalAmount).toBe(30000);
    const may = buildDemoAdvice(localMonthlyBudgets(store().scenario, 2026, 5), localMonthlyTransactions(store().scenario, 2026, 5));
    expect(may.advices[0]).toMatchObject({ category: '문화생활', over: 120000 });
  });
  it('preserves insurance exclusion in annual and leak aggregates without dropping it from monthly data', () => {
    store().updateCategory({ id: practice().id, category: '보험 / 세금', year: 2026, month: 10, generation: store().generation });
    expect(localMonthlyTransactions(store().scenario, 2026, 10).reduce((sum, row) => sum + row.amount, 0)).toBe(908000);
    expect(current().find(row => row.category === '보험 / 세금')).toMatchObject({ totalAmount: 30000, leakedAmount: 0 });
    expect(localAnnualTransactions(store().scenario).at(-1)?.totalAmount).toBe(878000);
    expect(localMonthlyBudgets(store().scenario, 2026, 10).some(row => row.category === '보험 / 세금')).toBe(false);
  });
  it('uses integer values and read-only missing category advice without fabricating a budget', () => {
    store().updateCategory({ id: practice().id, category: '교육', year: 2026, month: 10, generation: store().generation });
    const rows = localMonthlyBudgets(store().scenario, 2026, 10);
    expect(rows.find(row => row.category === '교육')).toMatchObject({ id: null, spending: 30000 });
    const advice = buildDemoAdvice(rows, localMonthlyTransactions(store().scenario, 2026, 10));
    expect(advice.advices.find(row => row.category === '교육')).toMatchObject({ basis: 'missing', spending: 30000, over: 0, pct: null });
  });
});

describe('bounded local edits and reset ownership', () => {
  it.each([0, -1, 1.5, Number.NaN, 999999])('rejects unknown/invalid budget ID %s', id => {
    const before = store().scenario;
    expect(store().updateBudget({ id, amount: 60000, year: 2026, month: 10, generation: store().generation })).toBe(false);
    expect(store().scenario).toBe(before);
  });
  it.each([-1, 0.1, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1])('rejects invalid amount %s', amount => {
    expect(store().updateBudget({ id: cafeBudget().id, amount, year: 2026, month: 10, generation: store().generation })).toBe(false);
  });
  it('rejects wrong month, unknown category and missing transaction', () => {
    const before = store().scenario;
    expect(store().updateBudget({ id: cafeBudget().id, amount: 60000, year: 2026, month: 9, generation: store().generation })).toBe(false);
    expect(store().updateCategory({ id: practice().id, category: '마트 / 편의점', year: 2026, month: 9, generation: store().generation })).toBe(false);
    expect(store().updateCategory({ id: practice().id, category: 'unknown', year: 2026, month: 10, generation: store().generation })).toBe(false);
    expect(store().updateCategory({ id: 999999, category: '카페', year: 2026, month: 10, generation: store().generation })).toBe(false);
    expect(store().scenario).toBe(before);
  });
  it('start and continue preserve current edits; reset is fresh and rejects callbacks from the old experience', () => {
    store().start(); const generation = store().generation;
    const id = cafeBudget().id; const transactionId = practice().id;
    store().updateBudget({ id, amount: 60000, year: 2026, month: 10, generation });
    const edited = store().scenario; store().start();
    expect(store().generation).toBe(generation); expect(store().scenario).toBe(edited);
    store().reset(); expect(store().generation).toBe(generation + 1); expect(store().scenario).toEqual(createLocalDemoScenario());
    expect(store().scenario).not.toBe(edited); expect(store().hasStarted).toBe(true);
    expect(store().updateBudget({ id, amount: 100000, year: 2026, month: 10, generation })).toBe(false);
    expect(store().updateCategory({ id: transactionId, category: '마트 / 편의점', year: 2026, month: 10, generation })).toBe(false);
    expect(leak()).toBe(18000);
  });
  it('end clears the experience and new start uses the initial sample', () => {
    store().start(); const generation = store().generation;
    store().updateCategory({ id: practice().id, category: '마트 / 편의점', year: 2026, month: 10, generation });
    store().end(); expect(store().hasStarted).toBe(false); expect(store().generation).toBe(generation + 1);
    store().start(); expect(store().hasStarted).toBe(true); expect(practice().category).toBe('카페'); expect(leak()).toBe(18000);
  });
});
