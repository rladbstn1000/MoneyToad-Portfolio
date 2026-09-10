import { authMode } from '../../auth/authMode';
import { useAuthStore } from '../../store/authStore';
import { useQuery } from '@tanstack/react-query';
import { getUserInfo } from '../services/users';
import { userQueryKeys } from '../queryKeys';

export const useUserInfoQuery = () => {
  const ready = useAuthStore(state => authMode !== 'demo' || state.status === 'authenticated');
  return useQuery({
    enabled: ready,
    queryKey: userQueryKeys.info(),
    queryFn: getUserInfo,
  });
};