import { describe, expect, it } from 'vitest';
import { parsePublicDemoSetCookie, type DemoCookieMode } from '../e2e/publicDemoCookie';

const issue: DemoCookieMode = { deployment: 'public-demo', operation: 'issue' };
const remove: DemoCookieMode = { deployment: 'public-demo', operation: 'delete' };
// Synthetic contents exist only during a test; assertion diagnostics receive metadata or fixed errors.
const contents = () => Array.from({ length: 24 }, (_, index) => String.fromCharCode(65 + index)).join('');
function header(value = contents(), age = '3599', attrs = '; Secure; HttpOnly; SameSite=Lax; Path=/api/auth/demo') {
  return ['demoRefreshToken', '=', value, '; Max-Age=', age, attrs].join('');
}
function response(...values: string[]) {
  return { async headerValues(name: string) {
    expect(name).toBe('set-cookie');
    return values;
  } };
}
async function errorCode(value: string, mode = issue) {
  try {
    await parsePublicDemoSetCookie(response(value), mode);
    return 'UNEXPECTED_PASS';
  } catch (error) {
    return error instanceof Error ? error.message : 'UNEXPECTED_THROW_TYPE';
  }
}

describe('E2E raw demo cookie retention and security metadata', () => {
  it.each([1, 3599, 3600])('accepts an issue lifetime of %i seconds without returning contents', async maxAge => {
    const metadata = await parsePublicDemoSetCookie(response(header(contents(), String(maxAge))), issue);
    expect(metadata).toEqual({ maxAge, secure: true, httpOnly: true, sameSite: 'Lax', path: '/api/auth/demo', hostOnly: true });
  });

  it('ignores Expires as a lifetime authority when Max-Age is present', async () => {
    const raw = header() + '; Expires=Thu, 01 Jan 1970 00:00:00 GMT';
    expect((await parsePublicDemoSetCookie(response(raw), issue)).maxAge).toBe(3599);
  });

  it('supports the explicit local HTTP contract without Secure', async () => {
    const raw = header().replace('; Secure', '');
    const metadata = await parsePublicDemoSetCookie(response(raw), { deployment: 'local-demo', operation: 'issue' });
    expect(metadata.secure).toBe(false);
  });

  it('accepts empty deletion with the same public scope', async () => {
    expect(await parsePublicDemoSetCookie(response(header('', '0')), remove)).toEqual({
      maxAge: 0, secure: true, httpOnly: true, sameSite: 'Lax', path: '/api/auth/demo', hostOnly: true,
    });
  });

  it('accepts empty local deletion with the same local scope', async () => {
    const raw = header('', '0').replace('; Secure', '');
    expect((await parsePublicDemoSetCookie(response(raw), { deployment: 'local-demo', operation: 'delete' })).maxAge).toBe(0);
  });

  it('handles case-insensitive attribute names and the Expires comma without splitting cookies', async () => {
    const raw = header().replace('HttpOnly', 'HTTPONLY').replace('SameSite=Lax', 'samesite=lax')
      + '; Expires=Tue, 01 Jan 2030 01:00:00 GMT';
    expect((await parsePublicDemoSetCookie(response(raw), issue)).httpOnly).toBe(true);
  });

  it.each(['0', '-1', '3601', '999999999999999999999'])('rejects out-of-range issue lifetime case %s', async age => {
    expect(await errorCode(header(contents(), age))).toMatch(/^DEMO_COOKIE_MAX_AGE_(INTEGER|RANGE)$/);
  });
  it.each(['', '1.5', '+1', '1e3', '001', ' 1', 'NaN', 'Infinity'])('rejects noncanonical integer age case %s', async age => {
    expect(await errorCode(header(contents(), age))).toBe('DEMO_COOKIE_MAX_AGE_INTEGER');
  });

  it.each([
    ['Max-Age absent', () => header().replace('; Max-Age=3599', ''), 'MAX_AGE_INTEGER'],
    ['wrong name', () => header().replace('demoRefreshToken', 'other'), 'NAME'],
    ['empty issue value', () => header(''), 'VALUE_SHAPE'],
    ['whitespace in value', () => header(contents() + ' '), 'VALUE_SHAPE'],
    ['control character in value', () => header(contents() + String.fromCharCode(0)), 'VALUE_SHAPE'],
    ['non-ASCII character in value', () => header(contents() + String.fromCharCode(128)), 'VALUE_SHAPE'],
    ['Domain present', () => header() + '; Domain=example.invalid', 'HOST_ONLY'],
    ['empty Domain', () => header() + '; Domain=', 'HOST_ONLY'],
    ['wrong Path', () => header().replace('Path=/api/auth/demo', 'Path=/'), 'PATH'],
    ['Path absent', () => header().replace('; Path=/api/auth/demo', ''), 'PATH'],
    ['wrong SameSite', () => header().replace('SameSite=Lax', 'SameSite=None'), 'SAME_SITE'],
    ['SameSite absent', () => header().replace('; SameSite=Lax', ''), 'SAME_SITE'],
    ['HttpOnly absent', () => header().replace('; HttpOnly', ''), 'HTTP_ONLY'],
    ['HttpOnly assigned', () => header().replace('HttpOnly', 'HttpOnly=false'), 'HTTP_ONLY'],
    ['public Secure absent', () => header().replace('; Secure', ''), 'SECURE'],
    ['Secure assigned', () => header().replace('Secure', 'Secure=false'), 'SECURE'],
    ['duplicate Max-Age', () => header() + '; Max-Age=10', 'ATTRIBUTE_DUPLICATE_OR_EMPTY'],
    ['duplicate case variant', () => header() + '; path=/api/auth/demo', 'ATTRIBUTE_DUPLICATE_OR_EMPTY'],
  ] as const)('rejects %s with a fixed safe error', async (_label, makeHeader, code) => {
    expect(await errorCode(makeHeader())).toBe(`DEMO_COOKIE_${code}`);
  });

  it('rejects Secure in the local HTTP contract', async () => {
    expect(await errorCode(header(), { deployment: 'local-demo', operation: 'issue' })).toBe('DEMO_COOKIE_SECURE');
  });
  it('rejects nonempty deletion', async () => {
    expect(await errorCode(header(contents(), '0'), remove)).toBe('DEMO_COOKIE_VALUE_SHAPE');
  });
  it('rejects a positive deletion lifetime', async () => {
    expect(await errorCode(header('', '1'), remove)).toBe('DEMO_COOKIE_MAX_AGE_RANGE');
  });
  it.each([0, 2])('requires exactly one raw Set-Cookie header; count %i', async count => {
    const values = Array.from({ length: count }, () => header());
    await expect(parsePublicDemoSetCookie(response(...values), issue)).rejects.toThrow('DEMO_COOKIE_COUNT');
  });
  it('never includes sensitive header contents in parser errors', async () => {
    const raw = header(contents()) + '; Domain=example.invalid';
    const code = await errorCode(raw);
    expect(code).toBe('DEMO_COOKIE_HOST_ONLY');
    expect(code.includes(contents())).toBe(false);
  });
  it('replaces upstream exception details with a fixed error', async () => {
    const failed = { async headerValues() { throw new Error(header()); } };
    await expect(parsePublicDemoSetCookie(failed, issue)).rejects.toThrow('DEMO_COOKIE_HEADER_READ_FAILED');
  });
});
