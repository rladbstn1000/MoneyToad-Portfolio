import type { MonthlyBudgetResponse, MonthlyTransaction } from '../types';

export type AdviceCategoryDetail = {
  mostSpent: { merchant: string; amount: number; date: string };
  mostFrequent: { merchant: string; count: number; totalAmount: number };
};
export type AdviceCard = { id: string; category: string; detail: string; preview: string } & (
  | { basis: 'missing'; spending: number; over: 0; pct: null }
  | { basis?: 'present'; over: number; pct: number | null }
);
export type AdviceDetails = Record<string, AdviceCategoryDetail>;

export const normalizeAdviceCategory = (value: string) => value.replace(/\s*\/\s*/g, '/').trim();

// Authored V1 reference values, not predictions or the visitor's mutable 12-month average.
const REFERENCES: Record<string, { average: number; comment: string }> = {
  '주거/통신': { average: 500000, comment: '고정 지출을 확인하고 사용하지 않는 요금 항목이 있는지 살펴보시오.' },
  '교통/차량': { average: 60000, comment: '이동 계획과 월 교통 한도를 함께 비교해 보시오.' },
  식비: { average: 115000, comment: '최근 식비가 늘어나는 샘플이오. 한 번의 식사 금액과 횟수를 나눠 살펴보시오.' },
  카페: { average: 410000 / 12, comment: '분류 연습 거래가 포함될 수 있소. 씀씀이에서 실제 카테고리를 먼저 확인하시오.' },
  '마트/편의점': { average: 90000, comment: '장보기 목록과 소비 한도를 비교하고 분류가 맞는지 확인하시오.' },
  문화생활: { average: 400000 / 12, comment: '특정 달의 문화생활 지출이 큰 샘플이오. 행사성 지출을 미리 계획해 보시오.' },
};
const amount = (value: number) => `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 }).format(value)}냥`;
const compareText = (a: string, b: string) => a < b ? -1 : a > b ? 1 : 0;

export function preparedDemoComment(category: string, hasBudget = true): string {
  if (!hasBudget) return '비교 기준 없음. 과소비 여부를 단정하지 않고 현재 소비 내역과 거래 분류를 살펴보시오.';
  return REFERENCES[normalizeAdviceCategory(category)]?.comment ?? '현재 소비와 설정한 한도를 비교해 보시오. 이 항목의 샘플 기준 평균은 제공하지 않소.';
}

/** Current amounts always come from real demo API data. This function performs no IO. */
export function buildDemoAdvice(budgets: MonthlyBudgetResponse[], transactions: MonthlyTransaction[]): {
  advices: AdviceCard[]; details: AdviceDetails;
} {
  const details: AdviceDetails = {};
  const advices = budgets.filter(row => row.id === null ? row.spending > 0 : row.spending > row.budget).map((row): AdviceCard => {
    const key = normalizeAdviceCategory(row.category);
    const matching = transactions.filter(item => normalizeAdviceCategory(item.category) === key);
    const sorted = [...matching].sort((a, b) => b.amount - a.amount || compareText(a.transactionDateTime, b.transactionDateTime) || compareText(a.merchantName, b.merchantName));
    const merchants = new Map<string, { merchant: string; count: number; totalAmount: number }>();
    for (const item of matching) {
      const previous = merchants.get(item.merchantName) ?? { merchant: item.merchantName, count: 0, totalAmount: 0 };
      merchants.set(item.merchantName, { merchant: previous.merchant, count: previous.count + 1, totalAmount: previous.totalAmount + item.amount });
    }
    const frequent = [...merchants.values()].sort((a, b) => b.count - a.count || b.totalAmount - a.totalAmount || compareText(a.merchant, b.merchant))[0];
    const most = sorted[0];
    if (most && frequent) details[key] = { mostSpent: { merchant: most.merchantName, amount: most.amount, date: most.transactionDateTime }, mostFrequent: frequent };
    if (row.id === null) return { id: row.category, category: row.category, basis: 'missing', spending: row.spending, over: 0, pct: null,
      detail: `'${row.category}'의 실제 소비는 ${amount(row.spending)}입니다.\n비교 기준 없음: 이 항목에 저장된 기준 예산이 없어 과소비 여부를 판단하지 않습니다.`,
      preview: `실제 소비 ${amount(row.spending)} · 비교 기준 없음` };
    const reference = REFERENCES[key]?.average;
    const pct = reference === undefined ? null : (row.spending - reference) / reference * 100;
    const over = row.spending - row.budget;
    const comparison = reference === undefined ? '이 항목의 샘플 기준 평균은 제공하지 않습니다.' : `초기 샘플 시나리오의 기준 평균은 ${amount(reference)}입니다.`;
    return { id: row.category, category: row.category, over, pct,
      detail: `'${row.category}'의 현재 한도는 ${amount(row.budget)}, 실제 소비는 ${amount(row.spending)}입니다.\n현재 누수는 ${amount(over)}입니다.\n${comparison}`,
      preview: `현재 누수 ${amount(over)}` };
  }).sort((a, b) => b.over - a.over || compareText(a.category, b.category));
  return { advices, details };
}
