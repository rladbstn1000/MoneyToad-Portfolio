import { describe, expect, it } from 'vitest';
import { findEditableTransaction, isTransactionId } from '../src/pages/chartDataBoundary';
import type { ChartPeriod } from '../src/pages/chartDataBoundary';
import type { MonthlyTransaction } from '../src/types';

// Pure helper tests. An undefined result establishes selection rejection here;
// actual DOM interaction and intercepted request counts belong to ChartPage.test.tsx.
const PERIOD: ChartPeriod = { year: 2026, month: 2 };
const CATEGORIES = [
  '식비', '카페', '마트 / 편의점', '문화생활', '교통 / 차량', '패션 / 미용',
  '생활용품', '주거 / 통신', '건강 / 병원', '교육', '경조사 / 회비', '보험 / 세금', '기타',
] as const;
type EditContext = Parameters<typeof findEditableTransaction>[0];

function apiTransaction(id = 1): MonthlyTransaction {
  return { id, transactionDateTime: '2026-02-03T12:00:00', amount: 1200,
    merchantName: '합성 API 거래', category: '식비' };
}

function readyContext(transaction = apiTransaction()): EditContext {
  return {
    id: transaction.id,
    category: '카페',
    period: { ...PERIOD },
    activePeriod: { ...PERIOD },
    transactions: [transaction],
    ready: true,
    fetching: false,
    placeholder: false,
    saving: false,
    allowedCategories: CATEGORIES,
  };
}

describe('chartDataBoundary helper unit: selection validation without DOM or network observation', () => {
  it.each([
    { label: 'API id 1', id: 1 },
    { label: 'largest safe integer', id: Number.MAX_SAFE_INTEGER },
  ])('accepts $label from the selected month without converting its identity', ({ id }) => {
    const transaction = Object.freeze(apiTransaction(id));

    expect(isTransactionId(id)).toBe(true);
    expect(findEditableTransaction(readyContext(transaction))).toBe(transaction);
  });

  it.each([
    { label: 'NaN', id: Number.NaN },
    { label: 'positive infinity', id: Number.POSITIVE_INFINITY },
    { label: 'negative infinity', id: Number.NEGATIVE_INFINITY },
    { label: 'numeric string', id: '1' },
    { label: 'generated sample string', id: '1-0' },
    { label: 'partially numeric string', id: '1abc' },
    { label: 'empty string', id: '' },
    { label: 'zero', id: 0 },
    { label: 'negative integer', id: -1 },
    { label: 'fraction', id: 1.5 },
    { label: 'unsafe integer', id: Number.MAX_SAFE_INTEGER + 1 },
    { label: 'null', id: null },
    { label: 'undefined', id: undefined },
  ])('rejects $label even when a candidate carries the same invalid ID', ({ id }) => {
    // JSON responses can violate the declared API type at runtime. Keep that
    // malformed candidate present so a missing-row check cannot mask the ID guard.
    const malformed = { ...apiTransaction(), id } as MonthlyTransaction;

    expect(isTransactionId(id)).toBe(false);
    expect(findEditableTransaction({ ...readyContext(malformed), id })).toBeUndefined();
  });

  it.each(['카페', '보험 / 세금', '기타'])('accepts permitted category %s for a current API row', category => {
    const transaction = apiTransaction();

    expect(findEditableTransaction({ ...readyContext(transaction), category })).toBe(transaction);
    expect(transaction.category).toBe('식비');
  });

  it('requires the selected ID to exist in the latest supplied monthly list', () => {
    const context = readyContext();
    const lists: Array<MonthlyTransaction[] | undefined> = [undefined, [], [apiTransaction(2)]];

    for (const transactions of lists) {
      expect(findEditableTransaction({ ...context, transactions })).toBeUndefined();
    }
  });

  it('rejects rows with another month, another year, or a non-month timestamp', () => {
    for (const transactionDateTime of [
      '2026-03-03T12:00:00', '2025-02-03T12:00:00', 'not-a-date',
    ]) {
      const transaction = { ...apiTransaction(), transactionDateTime };
      expect(findEditableTransaction(readyContext(transaction)), transactionDateTime).toBeUndefined();
    }
  });

  it('rejects the previous selection captured by a callback after the active month or year changes', () => {
    const capturedContext = readyContext();
    let activePeriod: ChartPeriod = { ...PERIOD };
    // This models captured helper inputs, not a mounted React callback.
    const previousSelection = () => findEditableTransaction({ ...capturedContext, activePeriod });

    expect(previousSelection()).toBe(capturedContext.transactions?.[0]);
    activePeriod = { year: 2026, month: 3 };
    expect(previousSelection()).toBeUndefined();
    activePeriod = { year: 2027, month: 2 };
    expect(previousSelection()).toBeUndefined();
  });

  it('requires an active month selection even when a cached row is available', () => {
    expect(findEditableTransaction({ ...readyContext(), activePeriod: null })).toBeUndefined();
  });

  it.each([
    { label: 'not ready, the shared helper input for pending or error queries', patch: { ready: false } },
    { label: 'fetching a replacement list', patch: { fetching: true } },
    { label: 'placeholder data', patch: { placeholder: true } },
    { label: 'a pending save', patch: { saving: true } },
  ])('rejects a valid retained row while $label', ({ patch }) => {
    expect(findEditableTransaction({ ...readyContext(), ...patch })).toBeUndefined();
  });

  it('rejects unknown categories, the aggregate filter, and an unchanged category', () => {
    for (const category of ['unsupported-category', '전체', '식비']) {
      expect(findEditableTransaction({ ...readyContext(), category }), category).toBeUndefined();
    }
  });

  it('rejects invalid period boundaries even when the active selection matches them', () => {
    const invalidPeriods: ChartPeriod[] = [
      { year: 0, month: 2 },
      { year: 2026.5, month: 2 },
      { year: 2026, month: 0 },
      { year: 2026, month: 13 },
      { year: 2026, month: 2.5 },
    ];
    for (const period of invalidPeriods) {
      // Give the row the same textual prefix so date mismatch is not the reason
      // the invalid period is rejected.
      const transaction = { ...apiTransaction(),
        transactionDateTime: `${period.year}-${String(period.month).padStart(2, '0')}-03T12:00:00` };
      expect(findEditableTransaction({ ...readyContext(transaction), period, activePeriod: period }),
        JSON.stringify(period)).toBeUndefined();
    }
  });
});
