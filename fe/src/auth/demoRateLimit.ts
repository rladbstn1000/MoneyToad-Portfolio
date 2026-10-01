import axios from 'axios';

/** Accept only the specified HTTP status + code, never a generic 429. */
export function demoRateLimitDelay(error: unknown, code: 'DEMO_LOGIN_RATE_LIMITED' | 'DEMO_READINESS_RATE_LIMITED'): number | null {
  if (!axios.isAxiosError<unknown>(error) || error.response?.status !== 429) return null;
  const data = error.response.data;
  if (typeof data !== 'object' || data === null || Array.isArray(data) || !('code' in data) || data.code !== code) return null;
  const value: unknown = error.response.headers['retry-after'];
  const seconds = typeof value === 'string' && /^[1-9][0-9]{0,3}$/.test(value) ? Number(value) : 0;
  return (seconds >= 1 && seconds <= 3_600 ? seconds : 60) * 1_000;
}
