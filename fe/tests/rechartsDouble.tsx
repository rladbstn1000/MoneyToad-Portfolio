import { cloneElement, createContext, isValidElement, useContext } from 'react';
import type { ReactElement, ReactNode } from 'react';

type ChartPoint = { month?: string; [key: string]: unknown };
type DotProperties = { cx: number; cy: number; index: number; payload: ChartPoint };
type Dot = ((properties: DotProperties) => ReactElement) | ReactElement;
const LineData = createContext<ChartPoint[]>([]);

// Only the SVG layout library is replaced. ChartPage supplies the real series
// and its actual dot callback retains the actual month-selection handler.
export function ResponsiveContainer({ children }: { children: ReactNode }) {
  return <div>{children}</div>;
}

export function LineChart({ data, children }: { data: ChartPoint[]; children: ReactNode }) {
  return (
    <LineData.Provider value={data}>
      <output data-testid="chart-line-data">{JSON.stringify(data)}</output>
      <svg aria-label="월간 소비 비교 그래프 대역">{children}</svg>
    </LineData.Provider>
  );
}

export function Line({ dataKey, name, dot }: { dataKey: string; name: string; dot?: Dot }) {
  const data = useContext(LineData);
  return <g>{data.map((payload, index) => {
    const properties = { index, payload, cx: 40 + index * 20, cy: 40 };
    const point = typeof dot === 'function' ? dot(properties) : dot;
    return isValidElement(point) ? cloneElement(point as ReactElement<Record<string, unknown>>, {
      key: `${dataKey}-${index}`,
      role: 'button',
      tabIndex: 0,
      'aria-label': `${name} ${payload.month} 선택`,
    }) : null;
  })}</g>;
}

export function PieChart({ children }: { children: ReactNode }) {
  return <svg aria-label="카테고리 그래프 대역">{children}</svg>;
}
export function Pie({ children }: { children?: ReactNode }) { return <g>{children}</g>; }
export function Cell() { return null; }
export function XAxis() { return null; }
export function YAxis() { return null; }
export function CartesianGrid() { return null; }
export function Tooltip() { return null; }
export function Legend() { return null; }
