import axios from 'axios';
import { isLocalDemo } from '../../auth/authMode';

// No business interceptor: RT exchange must never recursively refresh itself.
const transport = isLocalDemo ? null : axios.create({
  baseURL: import.meta.env.VITE_BACK_URL,
  withCredentials: true,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json', 'X-MoneyToad-Demo': '1' },
});

export const demoHttp = {
  async ready(signal: AbortSignal, timeout: number): Promise<boolean> {
    if (!transport) throw new Error('Server authentication is unavailable in local demo');
    const { data, headers, status } = await transport.get<unknown>('/api/auth/demo/ready', { signal, timeout });
    const contentType = String(headers['content-type'] ?? '').split(';')[0].trim().toLowerCase();
    return status === 200 && contentType === 'application/json' && typeof data === 'object' && data !== null
      && !Array.isArray(data) && Object.keys(data).length === 1 && 'ready' in data && data.ready === true;
  },
  async token(action: 'login' | 'reissue'): Promise<string> {
    if (!transport) throw new Error('Server authentication is unavailable in local demo');
    const { data } = await transport.post<{ accessToken?: unknown }>(`/api/auth/demo/${action}`, {},
      { timeout: action === 'login' ? 60_000 : 10_000 });
    if (typeof data?.accessToken !== 'string' || !data.accessToken.trim()) {
      throw new Error('Invalid demo response');
    }
    return data.accessToken;
  },
  async session(accessToken: string): Promise<number> {
    if (!transport) throw new Error('Server authentication is unavailable in local demo');
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
    if (!transport) throw new Error('Server authentication is unavailable in local demo');
    await transport.post('/api/auth/demo/logout', {}, { headers: { Authorization: `Bearer ${accessToken}` } });
  },
};
