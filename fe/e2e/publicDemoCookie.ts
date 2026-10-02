/** Raw response metadata only: never return cookie contents or expose headers in errors. */
export type CookieResponse = { headerValues(name: string): Promise<string[]> };
export type DemoCookieMode = {
  deployment: 'public-demo' | 'local-demo';
  operation: 'issue' | 'delete';
};
export type DemoCookieMetadata = {
  maxAge: number;
  secure: boolean;
  httpOnly: true;
  sameSite: 'Lax';
  path: '/api/auth/demo';
  hostOnly: true;
};

function requireCookie(condition: boolean, code: string): asserts condition {
  if (!condition) throw new Error(`DEMO_COOKIE_${code}`);
}

/**
 * Max-Age checks server-supplied relative retention. Browser storage and the
 * server session deadline are checked separately; their wall clocks are not compared.
 */
export async function parsePublicDemoSetCookie(
  response: CookieResponse, mode: DemoCookieMode,
): Promise<DemoCookieMetadata> {
  let headers: string[];
  try {
    headers = await response.headerValues('set-cookie');
  } catch {
    // Network/adapter exceptions may contain sensitive header diagnostics.
    throw new Error('DEMO_COOKIE_HEADER_READ_FAILED');
  }
  requireCookie(headers.length === 1, 'COUNT');
  const parts = headers[0].split(';');
  const pair = parts.shift()!;
  const separator = pair.indexOf('=');
  requireCookie(separator > 0 && pair.slice(0, separator) === 'demoRefreshToken', 'NAME');
  const value = pair.slice(separator + 1);
  requireCookie(mode.operation === 'delete' ? value === '' : value.length > 0, 'VALUE_SHAPE');
  requireCookie(Array.from(value).every(character => {
    const code = character.charCodeAt(0);
    return code > 32 && code < 127 && character !== ',';
  }), 'VALUE_SHAPE');

  const attributes = new Map<string, string | null>();
  for (const part of parts) {
    const segment = part.trim();
    const equals = segment.indexOf('=');
    const name = (equals < 0 ? segment : segment.slice(0, equals)).toLowerCase();
    const attribute = equals < 0 ? null : segment.slice(equals + 1);
    requireCookie(name.length > 0 && !attributes.has(name), 'ATTRIBUTE_DUPLICATE_OR_EMPTY');
    attributes.set(name, attribute);
  }
  requireCookie(!attributes.has('domain'), 'HOST_ONLY');
  requireCookie(attributes.get('path') === '/api/auth/demo', 'PATH');
  requireCookie(attributes.get('samesite')?.toLowerCase() === 'lax', 'SAME_SITE');
  requireCookie(attributes.has('httponly') && attributes.get('httponly') === null, 'HTTP_ONLY');
  const secure = attributes.has('secure');
  requireCookie(!secure || attributes.get('secure') === null, 'SECURE');
  requireCookie(secure === (mode.deployment === 'public-demo'), 'SECURE');
  const age = attributes.get('max-age');
  requireCookie(typeof age === 'string' && /^(?:0|[1-9][0-9]*)$/.test(age), 'MAX_AGE_INTEGER');
  const maxAge = Number(age);
  requireCookie(Number.isSafeInteger(maxAge)
    && (mode.operation === 'delete' ? maxAge === 0 : maxAge >= 1 && maxAge <= 3600), 'MAX_AGE_RANGE');

  // Expires is deliberately not a lifetime authority when Max-Age is present.
  return { maxAge, secure, httpOnly: true, sameSite: 'Lax', path: '/api/auth/demo', hostOnly: true };
}
