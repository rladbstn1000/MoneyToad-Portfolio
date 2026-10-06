import { useLocalDemoStore } from '../../demo/localDemoStore';
import { localAnnualTransactions, localMonthlyTransactions, localCategoryTransactions } from '../../demo/localDemoCalculations';
import { authMode, isLocalDemo } from '../../auth/authMode';
import { useAuthStore } from '../../store/authStore';
import { useQuery } from '@tanstack/react-query';
import { getYearTransaction, getPeerYearTransaction, getMonthlyTransactions, getCategoryTransactions } from '../services/transactions';
import { transactionQueryKeys } from '../queryKeys';

export const useYearTransactionQuery = () => {
  const ready = useAuthStore(state => isLocalDemo || authMode !== 'demo' || state.status === 'authenticated');
  return useQuery({
    enabled: ready,
    queryKey: transactionQueryKeys.year(),
    queryFn: isLocalDemo ? () => localAnnualTransactions(useLocalDemoStore.getState().scenario) : getYearTransaction,
    ...(isLocalDemo ? { initialData: () => localAnnualTransactions(useLocalDemoStore.getState().scenario), staleTime: Infinity } : {}),
  });
};

export const usePeerYearTransactionQuery = () => {
  return useQuery({
    enabled: authMode !== 'demo',
    queryKey: transactionQueryKeys.peerYear(),
    queryFn: getPeerYearTransaction,
  });
};

export const useMonthlyTransactionsQuery = (year: number, month: number) => {
  const ready = useAuthStore(state => isLocalDemo || authMode !== 'demo' || state.status === 'authenticated');
  return useQuery({
    enabled: ready && !!year && !!month,
    queryKey: transactionQueryKeys.monthly(year, month),
    queryFn: () => isLocalDemo ? localMonthlyTransactions(useLocalDemoStore.getState().scenario, year, month) : getMonthlyTransactions(year, month),
    ...(isLocalDemo ? { initialData: () => localMonthlyTransactions(useLocalDemoStore.getState().scenario, year, month), staleTime: Infinity } : {}),
    // Retain date validity as well as the authentication boundary.

  });
};

export const useCategoryTransactionsQuery = (year: number, month: number) => {
  const ready = useAuthStore(state => isLocalDemo || authMode !== 'demo' || state.status === 'authenticated');
  return useQuery({
    enabled: ready && !!year && !!month,
    queryKey: transactionQueryKeys.categories(year, month),
    queryFn: () => isLocalDemo ? localCategoryTransactions(useLocalDemoStore.getState().scenario, year, month) : getCategoryTransactions(year, month),
    ...(isLocalDemo ? { initialData: () => localCategoryTransactions(useLocalDemoStore.getState().scenario, year, month), staleTime: Infinity } : {}),
    // Retain date validity as well as the authentication boundary.

  });
};
