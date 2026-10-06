import { isLocalDemo } from '../../auth/authMode';
import { useLocalDemoStore } from '../../demo/localDemoStore';
import { localMonthlyBudgets, localYearlyBudgetLeaks } from '../../demo/localDemoCalculations';
import { useQuery } from '@tanstack/react-query';
import { getMonthlyBudgets, getYearlyBudgetLeaks } from '../services/budgets';
import { monthlyBudgetQueryKeys } from '../queryKeys';

export const useMonthlyBudgetsQuery = (year: number, month: number, options?: { retry: false }) => {
  return useQuery({
    queryKey: monthlyBudgetQueryKeys.monthly(year, month),
    ...options,
    queryFn: () => isLocalDemo ? localMonthlyBudgets(useLocalDemoStore.getState().scenario, year, month) : getMonthlyBudgets({ year, month }),
    ...(isLocalDemo ? { initialData: () => localMonthlyBudgets(useLocalDemoStore.getState().scenario, year, month), staleTime: Infinity } : {}),
    enabled: !!year && !!month,
  });
};

export const useYearlyBudgetLeaksQuery = () => {
  return useQuery({
    queryKey: monthlyBudgetQueryKeys.yearly(),
    queryFn: isLocalDemo ? () => localYearlyBudgetLeaks(useLocalDemoStore.getState().scenario) : getYearlyBudgetLeaks,
    ...(isLocalDemo ? { initialData: () => localYearlyBudgetLeaks(useLocalDemoStore.getState().scenario), staleTime: Infinity } : {}),
  });
};