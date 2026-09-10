import { describe, expect, it, vi } from 'vitest';
import { parseDemoChartPeriod } from '../src/pages/demoChartPeriod';

function yearEnding(anchor: string) {
  const [year, month] = anchor.split('-').map(Number);
  const last = year * 12 + month - 1;
  return Array.from({ length: 12 }, (_, index) => {
    const value = last - 11 + index;
    return { date: `${Math.floor(value / 12)}-${String(value % 12 + 1).padStart(2, '0')}`,
      totalAmount: 100, leaked: false };
  });
}

describe('demo annual API calendar boundary', () => {
  it.each(['2024-02', '2024-12', '2025-01', '2026-09'])('maps all twelve month indices from %s with no browser date access', anchor => {
    const dateSpy = vi.spyOn(globalThis, 'Date').mockImplementation(() => { throw new Error('Browser clock is not the demo calendar'); });
    try {
      const data = yearEnding(anchor);
      const parsed = parseDemoChartPeriod(data);
      expect(parsed?.anchor).toBe(anchor);
      expect(parsed?.monthIndex).toBe(Number(anchor.slice(5)) - 1);
      expect(parsed?.year).toBe(Number(anchor.slice(0, 4)));
      for (const row of data) {
        const [year, month] = row.date.split('-').map(Number);
        expect(parsed?.yearsByMonth[month - 1]).toBe(year);
      }
    } finally { dateSpy.mockRestore(); }
  });

  it.each([undefined, null, {}, [], yearEnding('2025-01').slice(1)])('rejects missing or incomplete period %j', input => {
    expect(parseDemoChartPeriod(input)).toBeNull();
  });

  it.each([
    { date: '2025-00' }, { date: '2025-13' }, { date: '2025-1' }, { date: '0000-01' },
    { date: '2025-01-01' }, { date: ' 2025-01' }, { totalAmount: -1 },
    { totalAmount: 1.5 }, { totalAmount: '100' }, { totalAmount: Number.NaN },
    { leaked: undefined }, { leaked: 'false' },
  ])('rejects malformed annual entry %j', patch => {
    const data = yearEnding('2025-01');
    expect(parseDemoChartPeriod([...data.slice(0, -1), { ...data[11], ...patch }])).toBeNull();
  });

  it('rejects duplicate, skipped or reversed periods even when there are twelve entries', () => {
    const data = yearEnding('2025-01');
    expect(parseDemoChartPeriod([...data.slice(0, 11), data[10]])).toBeNull();
    expect(parseDemoChartPeriod([...data.slice(0, 11), { ...data[11], date: '2025-02' }])).toBeNull();
    expect(parseDemoChartPeriod([...data].reverse())).toBeNull();
  });
});
