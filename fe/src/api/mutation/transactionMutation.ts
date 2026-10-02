import { useMutation, useQueryClient } from '@tanstack/react-query';
import { updateTransactionCategory } from '../services/transactions';
import type { UpdateCategoryRequest } from '../../types';
import { transactionQueryKeys, monthlyBudgetQueryKeys } from '../queryKeys';
import { authMode } from '../../auth/authMode';
import { useAuthStore } from '../../store/authStore';

export const useUpdateTransactionCategoryMutation = () => {
  const queryClient = useQueryClient();
  const generation = useAuthStore(state => state.generation);

  return useMutation({
    mutationFn: ({ transactionId, data }: { transactionId: number; data: UpdateCategoryRequest }) =>
      updateTransactionCategory(transactionId, data),
    onSuccess: () => {
      if (authMode === 'demo' && (useAuthStore.getState().generation !== generation
        || useAuthStore.getState().status !== 'authenticated')) return;
      if (authMode === 'demo') void queryClient.invalidateQueries({ queryKey: monthlyBudgetQueryKeys.all });
      // 모든 거래 관련 쿼리 캐시 무효화
      queryClient.invalidateQueries({
        queryKey: transactionQueryKeys.all,
      });
    },
  });
};