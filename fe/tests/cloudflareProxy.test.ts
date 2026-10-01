import { afterEach, describe, expect, it, vi } from 'vitest';
import { onRequest } from '../functions/api/[[path]]';
import { onRequest as rootHandler } from '../functions/api/index';
import routes from '../public/_routes.json';

const backend = 'https://backend.example.invalid';
const origin = 'https://pages.example.invalid';
// Runtime-only canary with the production canonical encoding; never a deployed credential.
const syntheticGateway = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const syntheticAuthorization = 'Bearer ' + crypto.randomUUID();
const syntheticCookie = 'demoRefreshToken=' + crypto.randomUUID();
const env = { BACKEND_ORIGIN: backend, DEMO_GATEWAY_SECRET: syntheticGateway };
function edgeRequest(url: string, init?: RequestInit): Request {
  const headers = new Headers(init?.headers);
  if (!headers.has('CF-Connecting-IP')) headers.set('CF-Connecting-IP', '203.0.113.9');
  return new Request(url, { ...init, headers });
}
const credentialUrl = new URL(backend);
credentialUrl.username = crypto.randomUUID();
credentialUrl.password = crypto.randomUUID();

afterEach(() => vi.unstubAllGlobals());

describe('Pages same-origin API boundary', () => {
  it.each([undefined, '', 'http://backend.example.invalid', `${backend}/`, `${backend}/api`,
    `${backend}?target=x`, `${backend}#x`, credentialUrl.toString(), origin])(
    'fails closed for an absent or invalid fixed HTTPS binding (%s)', async (value) => {
      const transport = vi.fn();
      vi.stubGlobal('fetch', transport);
      const response = await onRequest({ request: edgeRequest(`${origin}/api/users`), env: { BACKEND_ORIGIN: value } });
      expect(response.status).toBe(503);
      expect(await response.json()).toEqual({ error: 'BACKEND_NOT_CONFIGURED' });
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      expect(transport).not.toHaveBeenCalled();
    },
  );

  it('preserves method, encoded query, exact body and auth/browser headers in one fetch', async () => {
    const body = '{"category":"마트 / 편의점"}';
    const transport = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const sent = new Request(input, init);
      expect(sent.url).toBe(`${backend}/api/transactions/fixture?month=2024-02&x=a%2Fb&x=2`);
      expect(sent.method).toBe('PATCH');
      expect(sent.redirect).toBe('manual');
      expect(sent.cache).toBe('no-store');
      expect(await sent.text()).toBe(body);
      expect(sent.headers.get('Origin')).toBe(origin);
      expect(sent.headers.get('Content-Type')).toBe('application/json');
      expect(sent.headers.get('Authorization')).toBe(syntheticAuthorization);
      expect(sent.headers.get('Cookie')).toBe(syntheticCookie);
      expect(sent.headers.get('X-MoneyToad-Demo')).toBe('1');
      return new Response('{"updated":true}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/transactions/fixture?month=2024-02&x=a%2Fb&x=2`, {
      method: 'PATCH', body, headers: { Origin: origin, 'Content-Type': 'application/json',
        Authorization: syntheticAuthorization, Cookie: syntheticCookie, 'X-MoneyToad-Demo': '1' },
    }), env });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ updated: true });
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('removes untrusted forwarding and hop headers without inventing Origin', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const sent = new Request(input, init);
      for (const key of ['Host', 'Forwarded', 'X-Forwarded-Host', 'X-Forwarded-Proto', 'X-Forwarded-For',
        'X-Forwarded-Port', 'X-Real-IP', 'CF-Connecting-IP', 'True-Client-IP', 'Connection', 'Upgrade']) {
        expect(sent.headers.has(key)).toBe(false);
      }
      expect(sent.headers.has('Origin')).toBe(false);
      return new Response('{"ready":true}');
    });
    vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/ready`, { headers: {
      Host: 'spoof.example.invalid', Forwarded: 'proto=http', 'X-Forwarded-Host': 'spoof.example.invalid',
      'X-Forwarded-Proto': 'http', 'X-Forwarded-For': 'invalid', 'X-Forwarded-Port': '80', 'X-Real-IP': 'invalid',
      'CF-Connecting-IP': 'invalid', 'True-Client-IP': 'invalid', Connection: 'keep-alive', Upgrade: 'websocket',
    } }), env });
    expect(response.status).toBe(200);
    expect(response.headers.has('Access-Control-Allow-Origin')).toBe(false);
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('cannot select an upstream through query or API path', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      expect(url.origin).toBe(backend);
      expect(url.pathname).toBe('/api//other.example.invalid/resource');
      expect(url.searchParams.get('url')).toBe('https://other.example.invalid');
      return new Response('ok');
    });
    vi.stubGlobal('fetch', transport);
    expect((await onRequest({ request: edgeRequest(`${origin}/api//other.example.invalid/resource?url=https://other.example.invalid`), env })).status).toBe(200);
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it.each(['/chart', '/api-not-a-route', '/api/%2e%2e/private'])('never proxies outside API (%s)', async (path) => {
    const transport = vi.fn();
    vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(origin + path), env });
    expect(response.status).toBe(404);
    expect(transport).not.toHaveBeenCalled();
  });

  it.each([401, 403, 404, 409, 429, 503])('preserves upstream error status and body (%s)', async (status) => {
    const transport = vi.fn().mockResolvedValue(new Response('{"error":"fixture"}', { status,
      headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=3600' } }));
    vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/users`), env });
    expect(response.status).toBe(status);
    expect(await response.text()).toBe('{"error":"fixture"}');
    for (const header of ['Cache-Control', 'CDN-Cache-Control', 'Cloudflare-CDN-Cache-Control']) expect(response.headers.get(header)).toBe('no-store');
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('returns a redirect without following it or rewriting its location', async () => {
    const transport = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.redirect).toBe('manual');
      return new Response(null, { status: 307, headers: { Location: 'https://other.example.invalid/redirect' } });
    });
    vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/login`, { method: 'POST', body: '{}' }), env });
    expect(response.status).toBe(307);
    expect(response.headers.get('Location')).toBe('https://other.example.invalid/redirect');
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('preserves separate cookie lines including an Expires comma and exact scope', async () => {
    const cookies = ['demoRefreshToken=' + crypto.randomUUID() + '; Secure; HttpOnly; SameSite=Lax; Path=/api/auth/demo',
      'fixture=' + crypto.randomUUID() + '; Expires=Thu, 01 Oct 2026 00:00:00 GMT; Path=/api/auth/demo; Secure'];
    const headers = new Headers();
    for (const cookie of cookies) headers.append('Set-Cookie', cookie);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 201, headers })));
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/login`, { method: 'POST', body: '{}' }), env });
    expect(response.status).toBe(201);
    expect(response.headers.getSetCookie()).toEqual(cookies);
  });

  it('uses Workers getAll for separate Set-Cookie values when present', async () => {
    const upstream = new Response('{}');
    const getAll = vi.fn(() => ['fixture=' + crypto.randomUUID() + '; Path=/', 'fixtureTwo=' + crypto.randomUUID() + '; Path=/']);
    Object.defineProperty(upstream.headers, 'getAll', { value: getAll });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(upstream));
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/reissue`, { method: 'POST', body: '{}' }), env });
    expect(getAll).toHaveBeenCalledWith('Set-Cookie');
    expect(response.headers.getSetCookie()).toHaveLength(2);
  });

  it('fails closed instead of comma-splitting an unavailable cookie API', async () => {
    const upstream = new Response('{}', { headers: { 'Set-Cookie': 'fixture=' + crypto.randomUUID() } });
    Object.defineProperty(upstream.headers, 'getSetCookie', { value: undefined });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(upstream));
    expect((await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/reissue`, { method: 'POST', body: '{}' }), env })).status).toBe(502);
  });

  it('returns sanitized non-authentication failure without retry or SPA fallback', async () => {
    const transport = vi.fn().mockRejectedValue(new Error('PRIVATE_UPSTREAM_ERROR'));
    vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/login`, { method: 'POST', body: '{}' }), env });
    expect(response.status).toBe(502);
    expect(await response.json()).toEqual({ error: 'BAD_GATEWAY' });
    expect(response.headers.get('Content-Type')).toBe('application/json');
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('keeps the API root in the same handler and invocation routes exclude static UI', () => {
    expect(rootHandler).toBe(onRequest);
    expect(routes).toEqual({ version: 1, include: ['/api', '/api/*'], exclude: [] });
  });
});


describe('public demo server-only gateway', () => {
  it.each([undefined, '', 'invalid', 'B'.repeat(43)])('requires a canonical secret binding without an upstream call', async secret => {
    const transport = vi.fn(); vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/users`), env: { ...env, DEMO_GATEWAY_SECRET: secret } });
    expect(response.status).toBe(503);
    expect(await response.text()).not.toContain(syntheticGateway);
    expect(transport).not.toHaveBeenCalled();
  });
  it('overwrites browser internal headers with server binding and edge address, removing them from the response', async () => {
    const transport = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      expect(headers.get('X-MoneyToad-Gateway') === syntheticGateway).toBe(true);
      expect(headers.get('X-MoneyToad-Client-IP')).toBe('203.0.113.9');
      expect(headers.has('CF-Connecting-IP')).toBe(false);
      return new Response('{}', { headers: { 'X-MoneyToad-Gateway': syntheticGateway, 'X-MoneyToad-Client-IP': '203.0.113.9' } });
    });
    vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/login`, { method: 'POST', body: '{}', headers: {
      'X-MoneyToad-Gateway': 'BROWSER_FORGERY', 'X-MoneyToad-Client-IP': '192.0.2.1', 'X-Forwarded-For': '192.0.2.1',
    } }), env });
    expect(response.status).toBe(200);
    expect(response.headers.has('X-MoneyToad-Gateway')).toBe(false);
    expect(response.headers.has('X-MoneyToad-Client-IP')).toBe(false);
    expect(await response.text()).not.toContain(syntheticGateway);
    expect(transport).toHaveBeenCalledTimes(1);
  });
  it.each([undefined, '', 'example.invalid', '203.0.113.1, 203.0.113.2', '203.0.113.1:443', '203.0.113.1/24',
    'fe80::1%eth0', ':::', '1:2:3:4:5:6:7:8:9', '256.0.0.1', '01.2.3.4', '240.0.0.1'])('rejects missing, invalid or ambiguous edge address before forwarding login', async ip => {
    const transport = vi.fn(); vi.stubGlobal('fetch', transport);
    const request = new Request(`${origin}/api/auth/demo/login`, { method: 'POST', body: '{}', headers: ip === undefined ? {} : { 'CF-Connecting-IP': ip } });
    const response = await onRequest({ request, env });
    expect(response.status).toBe(403);
    expect(await response.json()).toMatchObject({ code: 'DEMO_CLIENT_ADDRESS_REJECTED' });
    expect(transport).not.toHaveBeenCalled();
  });
  it.each(['2001:db8::1', '2001:0DB8:0000:0000:0000:0000:0000:0001', '::ffff:203.0.113.9'])('supports actual IPv6 literals without DNS', async ip => {
    const transport = vi.fn().mockResolvedValue(new Response('{}')); vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/login`, { method: 'POST', headers: { 'CF-Connecting-IP': ip } }), env });
    expect(response.status).toBe(200); expect(transport).toHaveBeenCalledTimes(1);
  });
  it.each(['CF-Worker', 'CF-Connecting-IPv6'])('fails login closed for unsupported edge rewriting/subrequest paths', async header => {
    const transport = vi.fn(); vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: edgeRequest(`${origin}/api/auth/demo/login`, { method: 'POST', headers: { [header]: 'synthetic-ambiguous-input' } }), env });
    expect(response.status).toBe(403); expect(transport).not.toHaveBeenCalled();
  });
  it('does not make existing-session endpoints depend on a visitor IP', async () => {
    const transport = vi.fn().mockResolvedValue(new Response('{}')); vi.stubGlobal('fetch', transport);
    const response = await onRequest({ request: new Request(`${origin}/api/auth/demo/reissue`, { method: 'POST' }), env });
    expect(response.status).toBe(200); expect(transport).toHaveBeenCalledTimes(1);
    expect(new Headers(transport.mock.calls[0][1].headers).has('X-MoneyToad-Client-IP')).toBe(false);
  });
});
