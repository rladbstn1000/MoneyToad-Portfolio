export const userQueryKeys = {
  all: ['users'] as const,
  info: () => [...userQueryKeys.all, 'info'] as const,
} as const;

export const cardQueryKeys = {
  all: ['cards'] as const,
  info: () => [...cardQueryKeys.all, 'info'] as const,
} as const;

export const transactionQueryKeys = {
  all: ['transactions'] as const,
  year: () => [...transactionQueryKeys.all, 'year'] as const,
  peerYear: () => [...transactionQueryKeys.all, 'peer', 'year'] as const,
  monthly: (year: number, month: number) => [...transactionQueryKeys.all, 'monthly', year, month] as const,
  categories: (year: number, month: number) => [...transactionQueryKeys.all, 'categories', year, month] as const,
} as const;

export const monthlyBudgetQueryKeys = {
  all: ['monthlyBudgets'] as const,
  monthly: (year: number, month: number) => [...monthlyBudgetQueryKeys.all, 'monthly', year, month] as const,
  yearly: () => [...monthlyBudgetQueryKeys.all, 'yearly'] as const,
} as const;

export const doojoQueryKeys = {
  all: ['doojo'] as const,
  data: (year?: number, month?: number) => [...doojoQueryKeys.all, 'data', year, month] as const,
} as const;