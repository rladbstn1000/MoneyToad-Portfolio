import type { MonthlyTransaction } from '../types';

/** Authored V1 scenario mirrors DemoSeedScenario, never exported from a visitor database. */
export const LOCAL_DEMO_METADATA = Object.freeze({ version: 'V1', anchorYearMonth: '2026-10', anchorDate: '2026-10-28' });
export const LOCAL_PRACTICE_MERCHANT = '합성 장보기(분류 연습)';
export type LocalBudget = Readonly<{ id: number; yearMonth: string; category: string; amount: number; initialAmount: number }>;
export type LocalScenario = Readonly<{
  metadata: typeof LOCAL_DEMO_METADATA;
  transactions: readonly Readonly<MonthlyTransaction>[];
  budgets: readonly LocalBudget[];
}>;

const pad = (number: number) => String(number).padStart(2, '0');
export function localYearMonth(year: number, month: number): string | null {
  return Number.isSafeInteger(year) && year > 0 && year <= 9999 && Number.isSafeInteger(month) && month >= 1 && month <= 12
    ? `${String(year).padStart(4, '0')}-${pad(month)}` : null;
}
export function localScenarioMonths(): string[] {
  const [anchorYear, anchorMonth] = LOCAL_DEMO_METADATA.anchorYearMonth.split('-').map(Number);
  const anchorOrdinal = anchorYear * 12 + anchorMonth - 1;
  return Array.from({ length: 12 }, (_, index) => {
    const ordinal = anchorOrdinal - 11 + index;
    return `${Math.floor(ordinal / 12)}-${pad(ordinal % 12 + 1)}`;
  });
}

/** Each call owns fresh records; immutable initial values cannot be edited by presentation code. */
export function createLocalDemoScenario(): LocalScenario {
  const transactions: Readonly<MonthlyTransaction>[] = [];
  const budgets: LocalBudget[] = [];
  for (const [index, yearMonth] of localScenarioMonths().entries()) {
    const offset = index - 11;
    const add = (category: string, merchant: string, amount: number, days: number[]) => {
      for (const [visit, day] of days.entries()) transactions.push(Object.freeze({
        id: transactions.length + 1, transactionDateTime: `${yearMonth}-${pad(day)}T00:00:00`, amount,
        merchantName: `${merchant} ${pad(visit + 1)}`, category,
      }));
    };
    add('주거 / 통신', '합성 주거', 500000, [1]);
    add('교통 / 차량', '합성 교통', 15000, [2, 9, 16, 23]);
    add('식비', '합성 식사', offset === -2 ? 35000 : offset === -1 ? 40000 : offset === 0 ? 45000 : 25000, [3, 10, 17, 24]);
    add('카페', '합성 카페', 4000, offset === 0 ? [4, 7, 11, 14, 18, 21, 25] : [4, 7, 11, 14, 18, 21, 25, 28]);
    if (offset === 0) transactions.push(Object.freeze({ id: transactions.length + 1,
      transactionDateTime: `${yearMonth}-28T00:00:00`, amount: 30000, merchantName: LOCAL_PRACTICE_MERCHANT, category: '카페' }));
    add('마트 / 편의점', '합성 마트', 45000, [5, 19]);
    add('문화생활', '합성 문화', offset === -5 ? 180000 : 20000, [26]);
    for (const [category, amount] of [
      ['주거 / 통신', 600000], ['교통 / 차량', 80000], ['식비', 200000],
      ['카페', 40000], ['마트 / 편의점', 150000], ['문화생활', 60000],
    ] as const) budgets.push(Object.freeze({ id: budgets.length + 1, yearMonth, category, amount, initialAmount: amount }));
  }
  return Object.freeze({ metadata: LOCAL_DEMO_METADATA, transactions: Object.freeze(transactions), budgets: Object.freeze(budgets) });
}
