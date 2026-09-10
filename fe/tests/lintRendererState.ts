import type { ReactNode } from 'react';

export type DotInput = { cx?: number; cy?: number; index?: number; payload?: unknown };
export const rendererState: {
  tooltip: { active: boolean; label: string; payload: unknown[] };
  pieInput: Record<string, unknown>;
  dotOverride?: Partial<DotInput>;
  formatter?: (value: number, name: string) => ReactNode;
} = {
  tooltip: { active: true, label: '검증 월', payload: [] },
  pieInput: {},
};
