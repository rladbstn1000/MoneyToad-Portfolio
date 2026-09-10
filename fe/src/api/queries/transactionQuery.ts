import { authMode } from '../../auth/authMode';
import { useAuthStore } from '../../store/authStore';
import { useQuery } from '@tanstack/react-query';
import { getYearTransaction, getPeerYearTransaction, getMonthlyTransactions, getCategoryTransactions } from '../services/transactions';
import { transactionQueryKeys } from '../queryKeys';

export const useYearTransactionQuery = () => {
  const ready = useAuthStore(state => authMode !== 'demo' || state.status === 'authenticated');
  return useQuery({
    enabled: ready,
    queryKey: transactionQueryKeys.year(),
    queryFn: getYearTransaction,
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
  const ready = useAuthStore(state => authMode !== 'demo' || state.status === 'authenticated');
  return useQuery({
    enabled: ready && !!year && !!month,
    queryKey: transactionQueryKeys.monthly(year, month),
    queryFn: () => getMonthlyTransactions(year, month),
    // Retain date validity as well as the authentication boundary.

  });
};

export const useCategoryTransactionsQuery = (year: number, month: number) => {
  const ready = useAuthStore(state => authMode !== 'demo' || state.status === 'authenticated');
  return useQuery({
    enabled: ready && !!year && !!month,
    queryKey: transactionQueryKeys.categories(year, month),
    queryFn: () => getCategoryTransactions(year, month),
    // Retain date validity as well as the authentication boundary.

  });
};
