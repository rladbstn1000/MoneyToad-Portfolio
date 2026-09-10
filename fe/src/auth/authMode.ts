export type AuthMode = 'oauth' | 'demo';

export function parseAuthMode(value: unknown): AuthMode {
  if (value === undefined) return 'oauth';
  if (value === 'oauth' || value === 'demo') return value;
  throw new Error('VITE_AUTH_MODE must be oauth or demo');
}

export const authMode = parseAuthMode(import.meta.env.VITE_AUTH_MODE);
