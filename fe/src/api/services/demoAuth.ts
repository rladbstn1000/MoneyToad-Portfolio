import axios from 'axios';

// No business interceptor: RT exchange must never recursively refresh itself.
const transport = axios.create({
  baseURL: import.meta.env.VITE_BACK_URL,
  withCredentials: true,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json', 'X-MoneyToad-Demo': '1' },
});

export const demoHttp = {
  async token(action: 'login' | 'reissue'): Promise<string> {
    const { data } = await transport.post<{ accessToken?: unknown }>(`/api/auth/demo/${action}`, {});
    if (typeof data?.accessToken !== 'string' || !data.accessToken.trim()) {
      throw new Error('Invalid demo response');
    }
    return data.accessToken;
  },
  async session(accessToken: string): Promise<number> {
    const { data } = await transport.get<{ demo?: unknown; expiresAt?: unknown }>('/api/auth/demo/session', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const expiresAt = typeof data?.expiresAt === 'string' ? Date.parse(data.expiresAt) : NaN;
    if (data?.demo !== true || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      throw new Error('Invalid demo session response');
    }
    return expiresAt;
  },
  async logout(accessToken: string): Promise<void> {
    await transport.post('/api/auth/demo/logout', {}, { headers: { Authorization: `Bearer ${accessToken}` } });
  },
};
