import { useMutation, useQueryClient } from '@tanstack/react-query';
import { updateUserBasicInfo, type UpdateUserBasicInfo } from '../services/users';
import { userQueryKeys } from '../queryKeys';

export const useUpdateUserBasicInfoMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: UpdateUserBasicInfo) => updateUserBasicInfo(data),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: userQueryKeys.info(),
      });
    },
  });
};