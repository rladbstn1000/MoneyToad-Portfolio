import { useQuery } from '@tanstack/react-query';
import { getUserInfo } from '../services/users';
import { userQueryKeys } from '../queryKeys';

export const useUserInfoQuery = () => {
  return useQuery({
    queryKey: userQueryKeys.info(),
    queryFn: getUserInfo,
  });
};