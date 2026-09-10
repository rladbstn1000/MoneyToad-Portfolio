export type DemoChartPeriod = {
  anchor: string;
  year: number;
  monthIndex: number;
  yearsByMonth: number[];
};

/** The stored twelve API months are the only demo calendar authority. */
export function parseDemoChartPeriod(value: unknown): DemoChartPeriod | null {
  if (!Array.isArray(value) || value.length !== 12) return null;
  const yearsByMonth = Array<number>(12);
  let previous: number | undefined;
  let result: DemoChartPeriod | null = null;
  for (const entry of value) {
    if (!entry || typeof entry !== 'object' || typeof entry.date !== 'string'
      || !/^[0-9]{4}-(0[1-9]|1[0-2])$/.test(entry.date)
      || !Number.isSafeInteger(entry.totalAmount) || entry.totalAmount < 0
      || typeof entry.leaked !== 'boolean') return null;
    const [year, month] = entry.date.split('-').map(Number);
    if (year < 1) return null;
    const ordinal = year * 12 + month - 1;
    if (previous !== undefined && ordinal !== previous + 1) return null;
    yearsByMonth[month - 1] = year;
    result = { anchor: entry.date, year, monthIndex: month - 1, yearsByMonth };
    previous = ordinal;
  }
  return result;
}
