import { cloneElement, createContext, useContext } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { rendererState } from './lintRendererState';
import type { DotInput } from './lintRendererState';
export { ResponsiveContainer, XAxis, YAxis, CartesianGrid, Legend } from './rechartsDouble';
const Data = createContext<Record<string, unknown>[]>([]);

export function LineChart({ data, children }: { data: Record<string, unknown>[]; children: ReactNode }) {
  return <Data.Provider value={data}><div>{children}</div></Data.Provider>;
}
export function Line({ dataKey, dot }: { dataKey: string; dot: (input: DotInput) => ReactElement }) {
  const data = useContext(Data);
  return <svg data-testid={`dots-${dataKey}`}>{data.map((payload, index) =>
    <g key={index} data-testid={`${dataKey}-${index}`}>
      {dot({ cx: 40, cy: 40, index, payload, ...rendererState.dotOverride })}
    </g>)}
  </svg>;
}
export function Tooltip({ content, formatter }: {
  content?: ReactElement<typeof rendererState.tooltip>;
  formatter?: (value: number, name: string) => ReactNode;
}) {
  rendererState.formatter = formatter ?? rendererState.formatter;
  return content ? <div data-testid="actual-tooltip">{cloneElement(content, rendererState.tooltip)}</div> : null;
}
export function PieChart({ children }: { children: ReactNode }) { return <svg>{children}</svg>; }
export function Pie({ label, children }: { label: (input: unknown) => ReactNode; children: ReactNode }) {
  return <g data-testid="actual-pie-label">{label(rendererState.pieInput)}{children}</g>;
}
export function Cell({ fill }: { fill?: string }) { return <rect data-testid="pie-cell" fill={fill} />; }
