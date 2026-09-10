import { useMutation, useQueryClient } from '@tanstack/react-query';
import { updateBudget } from '../services/budgets';
import type { UpdateBudgetRequest, MonthlyBudgetResponse } from '../../types';
import { monthlyBudgetQueryKeys } from '../queryKeys';

export const useUpdateBudgetMutation = (year: number, month: number, onMutationComplete?: (budgetId: number) => void) => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: UpdateBudgetRequest) => updateBudget(data),
    onMutate: async (variables) => {
      const { budgetId, budget: newBudget } = variables;

      const queryKey = monthlyBudgetQueryKeys.monthly(year, month);

      // 진행 중인 쿼리들 취소
      await queryClient.cancelQueries({ queryKey });

      // 이전 데이터 스냅샷 저장
      const previousData = queryClient.getQueryData(queryKey);

      // 낙관적 업데이트
      queryClient.setQueryData(queryKey, (old: MonthlyBudgetResponse[] | undefined) => {
        if (!old) return old;

        return old.map((item: MonthlyBudgetResponse) =>
          item.id === budgetId
            ? { ...item, budget: newBudget }
            : item
        );
      });

      return { previousData, queryKey };
    },
    onError: (_err, variables, context) => {
      // 에러 시 이전 데이터로 롤백
      if (context?.previousData && context?.queryKey) {
        queryClient.setQueryData(context.queryKey, context.previousData);
      }
      // pending 상태 정리
      if (onMutationComplete) {
        onMutationComplete(variables.budgetId);
      }
    },
    onSuccess: (_data, variables) => {

      // pending 상태 정리
      if (onMutationComplete) {
        onMutationComplete(variables.budgetId);
      }

      // 필요한 경우에만 특정 쿼리 재검증
      queryClient.invalidateQueries({ queryKey: monthlyBudgetQueryKeys.all });

    },
  });
};