import { aiRequest, REQUEST_METHOD } from '../client';
import type { ApiDoojoResponse, DoojoResponse } from '../../types';
import { adaptDoojoResponse } from '../../types';

export const getDoojoData = async (year?: number, month?: number): Promise<DoojoResponse> => {
  const queryParams = year && month ? `?year=${year}&month=${month}` : '';
  
  const response = await aiRequest<ApiDoojoResponse>({
    method: REQUEST_METHOD.GET,
    url: `${import.meta.env.VITE_BACK_URL}/api/ai/data/doojo${queryParams}`,
  });

  return adaptDoojoResponse(response);
};