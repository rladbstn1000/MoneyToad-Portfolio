import { edgeClientAddress } from '../../server/demoClientAddress';

/** Pages root is fe; BACKEND_ORIGIN is a server binding, never a VITE value. */
type Context = { request: Request; env: { BACKEND_ORIGIN?: string; DEMO_GATEWAY_SECRET?: string } };

const hopHeaders = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade',
]);

function failure(status: number, error: string, code?: string): Response {
  return new Response(JSON.stringify({ error, ...(code ? { code } : {}) }), {
    status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });
}

function configuredOrigin(value: string | undefined): URL | null {
  if (!value) return null;
  try {
    const origin = new URL(value);
    if (origin.protocol !== 'https:' || origin.username || origin.password || origin.search || origin.hash
      || origin.pathname !== '/' || value !== origin.origin) return null;
    return origin;
  } catch { return null; }
}

function responseCookies(headers: Headers): string[] {
  // Workers retains getAll specifically for Set-Cookie. Node's standards-based
  // local verifier uses getSetCookie. Never split a combined string on commas.
  if ('getAll' in headers && typeof headers.getAll === 'function') {
    const cookies: unknown = headers.getAll('Set-Cookie');
    if (Array.isArray(cookies) && cookies.every((item): item is string => typeof item === 'string')) return cookies;
    throw new Error('Invalid upstream cookie headers');
  }
  if (typeof headers.getSetCookie === 'function') return headers.getSetCookie();
  if (!headers.has('Set-Cookie')) return [];
  throw new Error('Separate upstream cookie headers unavailable');
}

export async function onRequest({ request, env }: Context): Promise<Response> {
  const incoming = new URL(request.url);
  if (incoming.pathname !== '/api' && !incoming.pathname.startsWith('/api/')) {
    return failure(404, 'NOT_FOUND');
  }
  const upstream = configuredOrigin(env.BACKEND_ORIGIN);
  if (!upstream || upstream.origin === incoming.origin) return failure(503, 'BACKEND_NOT_CONFIGURED');
  const secret = env.DEMO_GATEWAY_SECRET;
  // Canonical unpadded base64url of 32 bytes, including the last two zero bits.
  if (!secret || !/^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(secret)) return failure(503, 'GATEWAY_NOT_CONFIGURED');
  const clientAddress = edgeClientAddress(request.headers);
  if (request.method === 'POST' && incoming.pathname === '/api/auth/demo/login' && !clientAddress) {
    return failure(403, 'Forbidden', 'DEMO_CLIENT_ADDRESS_REJECTED');
  }
  // Assign URL components separately: even an absolute URL in the query/path
  // cannot replace the configured origin or become an open proxy destination.
  upstream.pathname = incoming.pathname;
  upstream.search = incoming.search;
  const headers = new Headers(request.headers);
  for (const name of [...headers.keys()]) {
    const lower = name.toLowerCase();
    if (hopHeaders.has(lower) || lower === 'host' || lower === 'forwarded'
      || lower.startsWith('x-forwarded-') || lower === 'x-real-ip'
      || lower === 'cf-connecting-ip' || lower === 'cf-connecting-ipv6' || lower === 'cf-worker'
      || lower === 'true-client-ip' || lower === 'x-moneytoad-gateway' || lower === 'x-moneytoad-client-ip') headers.delete(name);
  }
  headers.set('X-MoneyToad-Gateway', secret);
  if (clientAddress) headers.set('X-MoneyToad-Client-IP', clientAddress);
  try {
    const forwarded = new Request(upstream, request);
    // One fetch only. In particular, never follow a redirect carrying auth or
    // Cookie to another origin, retry a POST, or use the Workers Cache API.
    const response = await fetch(forwarded, { headers, redirect: 'manual', cache: 'no-store' });
    const responseHeaders = new Headers();
    response.headers.forEach((value, name) => {
      if (!['set-cookie', 'x-moneytoad-gateway', 'x-moneytoad-client-ip'].includes(name.toLowerCase()) && !hopHeaders.has(name.toLowerCase())) responseHeaders.append(name, value);
    });
    for (const cookie of responseCookies(response.headers)) responseHeaders.append('Set-Cookie', cookie);
    responseHeaders.set('Cache-Control', 'no-store');
    responseHeaders.set('CDN-Cache-Control', 'no-store');
    responseHeaders.set('Cloudflare-CDN-Cache-Control', 'no-store');
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers: responseHeaders });
  } catch {
    // Do not expose hostnames, driver errors, requests, cookies, or auth values.
    return failure(502, 'BAD_GATEWAY');
  }
}
