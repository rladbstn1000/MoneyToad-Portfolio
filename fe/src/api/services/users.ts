import { request, REQUEST_METHOD } from '../client';
import type { UserInfo } from '../../types/user';
import type { Gender } from '../../types';

export const getUserInfo = async () => {
  const response = await request<UserInfo>({
    method: REQUEST_METHOD.GET,
    url: `${import.meta.env.VITE_BACK_URL}/api/users`,
  });

  return response;
};
export interface UpdateUserBasicInfo {
  gender: Gender;
  age: number;
}

export const updateUserBasicInfo = async (data: UpdateUserBasicInfo) => {
  const response = await request<UserInfo>({
    method: REQUEST_METHOD.PATCH,
    url: `${import.meta.env.VITE_BACK_URL}/api/users`,
    data,
  });

  return response;
};
