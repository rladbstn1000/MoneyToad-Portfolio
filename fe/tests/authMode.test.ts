import { describe, expect, it } from 'vitest';
import { parseAuthMode } from '../src/auth/authMode';

describe('explicit authentication mode', () => {
  it('defaults to the original OAuth mode only when omitted', () => expect(parseAuthMode(undefined)).toBe('oauth'));
  it.each(['oauth', 'demo'] as const)('accepts %s', value => expect(parseAuthMode(value)).toBe(value));
  it.each(['', ' ', 'DEMO', ' demo', 'demo ', 'unknown', null, true])('rejects invalid setting %s', value => {
    expect(() => parseAuthMode(value)).toThrow('VITE_AUTH_MODE');
  });
});
