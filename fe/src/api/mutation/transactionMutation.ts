import { useLocalDemoStore } from '../../demo/localDemoStore';
import { refreshLocalDemoQueries } from '../../demo/localDemoQueries';
import { localMonthlyTransactions } from '../../demo/localDemoCalculations';
import type { MonthlyTransaction } from '../../types';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { updateTransactionCategory } from '../services/transactions';
import type { UpdateCategoryRequest } from '../../types';
import { transactionQueryKeys, monthlyBudgetQueryKeys } from '../queryKeys';
import { authMode, isLocalDemo } from '../../auth/authMode';
import { useAuthStore } from '../../store/authStore';

type CategoryMutationInput = { transactionId: number; data: UpdateCategoryRequest; period?: { year: number; month: number }; localGeneration?: number };

export const useUpdateTransactionCategoryMutation = () => {
  const queryClient = useQueryClient();
  const generation = useAuthStore(state => state.generation);
  const localGeneration = useLocalDemoStore(state => state.generation);

  const mutation = useMutation({
    mutationFn: async ({ transactionId, data, period, localGeneration: requestedGeneration }: CategoryMutationInput) => {
      if (!isLocalDemo) return updateTransactionCategory(transactionId, data);
      const rows = period ? queryClient.getQueryData<MonthlyTransaction[]>(transactionQueryKeys.monthly(period.year, period.month)) : undefined;
      if (requestedGeneration !== useLocalDemoStore.getState().generation || !period || !rows?.some(row => row.id === transactionId)
        || !useLocalDemoStore.getState().updateCategory({ id: transactionId, category: data.category, ...period, generation: requestedGeneration })) {
        throw new Error('현재 샘플 거래를 확인해 주세요.');
      }
      refreshLocalDemoQueries(queryClient, requestedGeneration);
      return localMonthlyTransactions(useLocalDemoStore.getState().scenario, period.year, period.month).find(row => row.id === transactionId)!;
    },
    onSuccess: () => {
      if (isLocalDemo) return;
      if (authMode === 'demo' && (useAuthStore.getState().generation !== generation
        || useAuthStore.getState().status !== 'authenticated')) return;
      if (authMode === 'demo') void queryClient.invalidateQueries({ queryKey: monthlyBudgetQueryKeys.all });
      // 모든 거래 관련 쿼리 캐시 무효화
      queryClient.invalidateQueries({
        queryKey: transactionQueryKeys.all,
      });
    },
  });
  if (!isLocalDemo) return mutation;
  // Capture ownership at dispatch, before React Query may defer mutationFn to a later microtask.
  return {
    ...mutation,
    mutate: (variables: CategoryMutationInput, options?: Parameters<typeof mutation.mutate>[1]) =>
      mutation.mutate({ ...variables, localGeneration }, options),
    mutateAsync: (variables: CategoryMutationInput, options?: Parameters<typeof mutation.mutateAsync>[1]) =>
      mutation.mutateAsync({ ...variables, localGeneration }, options),
  };
};