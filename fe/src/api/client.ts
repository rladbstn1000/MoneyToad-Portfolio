import axios from 'axios';
import type { AxiosRequestConfig } from 'axios';
import { setupAuthInterceptor } from './interceptors';

const defaultHeaders = {
  'Content-Type': 'application/json',
};

export const REQUEST_METHOD = {
  GET: 'get',
  POST: 'post',
  PUT: 'put',
  DELETE: 'delete',
  PATCH: 'patch',
} as const;

// axios 인스턴스 생성
const axiosInstance = axios.create({
  baseURL: import.meta.env.VITE_BACK_URL,
  headers: defaultHeaders,
  withCredentials: true,
});

// 인증 인터셉터 설정
setupAuthInterceptor(axiosInstance);

// 공통 응답 타입 (백엔드에 상황 보고 조정)
type ApiResponseForm<T> = {
  data: T;
  success?: boolean;
  message?: string;
};

// 에러 응답 타입
type ApiError = {
  message: string;
  code?: number;
  details?: unknown;
};

// 공통 request 함수
const request = async <ResponseType, RequestType = unknown>(
  options: AxiosRequestConfig<RequestType>,
) => {
  try {
    const { data } =
      await axiosInstance.request<ResponseType>(options);
    return data;
  } catch (error: unknown) {
    const { response } = error as {
      response?: { status: number; data?: { message?: string } };
    };

    // 에러 응답 표준화
    const errorResponse: ApiError = {
      message:
        response?.data?.message ??
        (error as Error)?.message ??
        '알 수 없는 오류가 발생했습니다.',
      code: response?.status,
      details: response?.data,
    };

    throw errorResponse;
  }
};

// AI API 전용 axios 인스턴스 (쿠키 전송하지 않음)
const aiAxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_BACK_URL,
  headers: defaultHeaders,
  withCredentials: false,
});

// AI API 인스턴스에도 인증 인터셉터 설정
setupAuthInterceptor(aiAxiosInstance);

// AI API 전용 request 함수 (인증 없이)
const aiRequest = async <ResponseType, RequestType = unknown>(
  options: AxiosRequestConfig<RequestType>,
) => {
  try {
    const { data } =
      await aiAxiosInstance.request<ResponseType>(options);
    return data;
  } catch (error: unknown) {
    const { response } = error as {
      response?: { status: number; data?: { message?: string } };
    };

    // 에러 응답 표준화
    const errorResponse: ApiError = {
      message:
        response?.data?.message ??
        (error as Error)?.message ??
        '알 수 없는 오류가 발생했습니다.',
      code: response?.status,
      details: response?.data,
    };

    throw errorResponse;
  }
};

export { request, aiRequest, axiosInstance };
export type { ApiResponseForm, ApiError };
