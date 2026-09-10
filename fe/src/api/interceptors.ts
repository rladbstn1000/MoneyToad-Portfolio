import type { AxiosInstance } from "axios";
import { useAuthStore } from "../store/authStore";
import { reissueToken } from "./services/auth";

export const setupAuthInterceptor = (axiosInstance: AxiosInstance) => {
  let isRefreshing = false;

  // 요청 인터셉터 - 모든 API 요청에 accessToken 자동 추가
  axiosInstance.interceptors.request.use(
    (config) => {
      const { accessToken } = useAuthStore.getState();

      if (accessToken) {
        config.headers.Authorization = `Bearer ${accessToken}`;
      }

      return config;
    },
    (error) => Promise.reject(error)
  );

  // 응답 인터셉터 - 401 에러 시 토큰 재발급 처리
  axiosInstance.interceptors.response.use(
    (response) => response,
    async (error) => {
      const originalRequest = error.config;

      // 401 에러이고 아직 재시도하지 않은 경우
      // Network Error의 경우도 401일 가능성이 있으므로 추가 체크
      const is401Error =
        error.response?.status === 401 ||
        (error.code === "ERR_NETWORK" && error.request?.status === 0);

      if (is401Error && !originalRequest._retry && !isRefreshing) {
        originalRequest._retry = true;
        isRefreshing = true;

        try {
          // const { accessToken: oldToken } = useAuthStore.getState();

          // reissue 엔드포인트로 POST 요청
          const { accessToken } = await reissueToken();

          // 새 토큰으로 store 업데이트
          const { setAccessToken } = useAuthStore.getState();
          setAccessToken(accessToken);

          // 원래 요청에 새 토큰으로 재시도
          originalRequest.headers.Authorization = `Bearer ${accessToken}`;
          return axiosInstance(originalRequest);
        } catch (reissueError) {
          alert("로그인이 필요합니다.");
          window.location.href = "/";
          return Promise.reject(reissueError);
        } finally {
          isRefreshing = false;
        }
      }

      return Promise.reject(error);
    }
  );
};