import { request, REQUEST_METHOD } from '../client';
import { useMutation } from '@tanstack/react-query';

export interface ReissueTokenResponse {
  accessToken: string;
}

export const reissueToken = async () => {
  const response = await request<ReissueTokenResponse>({
    method: REQUEST_METHOD.POST,
    url: `${import.meta.env.VITE_BACK_URL}/api/auth/reissue`,
  });

  return response;
};

export const logout = async () => {
  await request({
    method: REQUEST_METHOD.POST,
    url: `${import.meta.env.VITE_BACK_URL}/api/auth/logout`,
  });
};

export const useLogoutMutation = () => {
  return useMutation({
    mutationFn: logout,
    onSettled: () => {
      window.location.href = '/';
    },
  });
};