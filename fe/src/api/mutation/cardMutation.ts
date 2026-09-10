import { useMutation, useQueryClient } from '@tanstack/react-query';
import { registerCard, deleteCard, type RegisterCardRequest, updateCard } from '../services/cards';
import { cardQueryKeys } from '../queryKeys';

export const useRegisterCardMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: RegisterCardRequest) => registerCard(data),
    onSuccess: (newCardData) => {
      // 즉시 캐시 업데이트로 빠른 UI 반영
      queryClient.setQueryData(cardQueryKeys.info(), newCardData);
    },
  });
};

export const useDeleteCardMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => deleteCard(),
    onSuccess: () => {
      // 즉시 캐시를 null로 설정하여 빠른 UI 반영
      queryClient.setQueryData(cardQueryKeys.info(), null);
    },
  });
};

export const useUpdateCardMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: RegisterCardRequest) => updateCard(data),
    onSuccess: (newCardData) => {
      queryClient.setQueryData(cardQueryKeys.info(), newCardData);
    },
  });
};