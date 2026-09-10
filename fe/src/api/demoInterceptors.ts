import axios from 'axios';
import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '../store/authStore';
import { assertDemoGeneration, DemoAuthError, endDemo, refreshDemo } from '../auth/demoCoordinator';

type DemoRequest = InternalAxiosRequestConfig & {
  demoVisit?: number; demoRevision?: number; demoRetried?: boolean;
};

export function setupDemoInterceptor(client: AxiosInstance): void {
  client.interceptors.request.use((config: DemoRequest) => {
    const state = useAuthStore.getState();
    config.demoVisit ??= state.generation;
    assertDemoGeneration(config.demoVisit);
    if (state.status !== 'authenticated' || !state.accessToken) throw new DemoAuthError('체험 인증이 필요합니다.');
    const target = new URL(config.url ?? '', config.baseURL);
    if (target.origin !== new URL(import.meta.env.VITE_BACK_URL).origin || target.username || target.password) {
      throw new DemoAuthError('허용되지 않은 요청 주소입니다.');
    }
    config.demoRevision = state.revision;
    config.headers.Authorization = `Bearer ${state.accessToken}`;
    return config;
  });
  client.interceptors.response.use(response => {
    assertDemoGeneration((response.config as DemoRequest).demoVisit!);
    return response;
  }, async (error: unknown) => {
    if (!axios.isAxiosError(error) || !error.config) throw error;
    const config = error.config as DemoRequest;
    assertDemoGeneration(config.demoVisit!);
    if (error.response?.status !== 401) throw error;
    if (config.demoRetried) {
      endDemo();
      throw new DemoAuthError('체험이 종료되었습니다.', 401);
    }
    config.demoRetried = true;
    if (config.demoRevision === useAuthStore.getState().revision) await refreshDemo(config.demoVisit!);
    assertDemoGeneration(config.demoVisit!);
    return client.request(config);
  });
}
