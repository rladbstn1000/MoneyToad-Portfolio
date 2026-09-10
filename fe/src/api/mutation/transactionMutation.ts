import { useMutation, useQueryClient } from '@tanstack/react-query';
import { updateTransactionCategory } from '../services/transactions';
import type { UpdateCategoryRequest } from '../../types';
import { transactionQueryKeys } from '../queryKeys';

export const useUpdateTransactionCategoryMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ transactionId, data }: { transactionId: number; data: UpdateCategoryRequest }) =>
      updateTransactionCategory(transactionId, data),
    onSuccess: () => {
      // 모든 거래 관련 쿼리 캐시 무효화
      queryClient.invalidateQueries({
        queryKey: transactionQueryKeys.all,
      });
    },
  });
};