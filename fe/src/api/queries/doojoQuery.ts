import { useQuery } from '@tanstack/react-query';
import { getDoojoData } from '../services/doojo';
import { doojoQueryKeys } from '../queryKeys';

export const useDoojoQuery = (year?: number, month?: number) => {
  return useQuery({
    queryKey: doojoQueryKeys.data(year, month),
    queryFn: () => getDoojoData(year, month),
  });
};