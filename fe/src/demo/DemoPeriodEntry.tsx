import { Navigate } from 'react-router-dom';
import { useDemoPeriod } from './useDemoPeriod';

export default function DemoPeriodEntry() {
  const { query, period } = useDemoPeriod();
  if (query.isPending) return <p role="status">체험 기준월을 확인하고 있습니다.</p>;
  if (query.isError || !period) return <section role="status">
    <p>체험 기준월을 불러오지 못했습니다. 세션은 유지됩니다.</p>
    <button type="button" onClick={() => void query.refetch()}>데이터 다시 시도</button>
  </section>;
  return <Navigate to={`/pot/${period.monthIndex + 1}`} replace />;
}
