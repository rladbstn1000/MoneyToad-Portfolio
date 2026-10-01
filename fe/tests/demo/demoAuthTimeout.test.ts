import { beforeEach, describe, expect, it, vi } from 'vitest';

const requests = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), create: vi.fn() }));
vi.mock('axios', () => ({ default: { create: requests.create.mockReturnValue({ get: requests.get, post: requests.post }) } }));

beforeEach(() => {
  requests.get.mockReset(); requests.post.mockReset();
  requests.post.mockResolvedValue({ data: { accessToken: 'synthetic-timeout-access' } });
});

describe('only explicit login receives the larger timeout', () => {
  it('retains the shared 10s default and credentials without an interceptor', async () => {
    await import('../../src/api/services/demoAuth');
    expect(requests.create).toHaveBeenCalledWith({ baseURL: 'http://127.0.0.1:18080',
      withCredentials: true, timeout: 10_000,
      headers: { 'Content-Type': 'application/json', 'X-MoneyToad-Demo': '1' } });
  });
  it('uses 60s for one login POST with actual empty JSON', async () => {
    const { demoHttp } = await import('../../src/api/services/demoAuth');
    await demoHttp.token('login');
    expect(requests.post.mock.calls).toEqual([['/api/auth/demo/login', {}, { timeout: 60_000 }]]);
  });
  it('keeps reissue at 10s and never gives logout/session the login override', async () => {
    const { demoHttp } = await import('../../src/api/services/demoAuth');
    requests.get.mockResolvedValue({ data: { demo: true, expiresAt: new Date(Date.now() + 60_000).toISOString() } });
    await demoHttp.token('reissue'); await demoHttp.session('synthetic-timeout-access');
    await demoHttp.logout('synthetic-timeout-access');
    expect(requests.post.mock.calls[0]).toEqual(['/api/auth/demo/reissue', {}, { timeout: 10_000 }]);
    expect(requests.post.mock.calls[1][2]).not.toHaveProperty('timeout');
    expect(requests.get.mock.calls[0][1]).not.toHaveProperty('timeout');
  });
});
