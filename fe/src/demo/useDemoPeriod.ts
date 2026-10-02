import { useYearTransactionQuery } from '../api/queries/transactionQuery';
import { parseDemoChartPeriod } from '../pages/demoChartPeriod';

/** Reuse Chart's stored twelve-month authority, never the browser calendar. */
export function useDemoPeriod() {
  const query = useYearTransactionQuery();
  const period = parseDemoChartPeriod(query.data);
  return { query, period };
}
