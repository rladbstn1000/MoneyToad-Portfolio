import { useQuery } from '@tanstack/react-query';
import { getCardInfo } from '../services/cards';
import { cardQueryKeys } from '../queryKeys';

export const useCardInfoQuery = () => {
  return useQuery({
    queryKey: cardQueryKeys.info(),
    queryFn: getCardInfo,
    retry: false,
  });
};