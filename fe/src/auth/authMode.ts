export type AuthMode = 'oauth' | 'demo';
export type DemoDataMode = 'local' | 'remote';

export function parseAuthMode(value: unknown): AuthMode {
  if (value === undefined) return 'oauth';
  if (value === 'oauth' || value === 'demo') return value;
  throw new Error('VITE_AUTH_MODE must be oauth or demo');
}

export function parseDemoDataMode(value: unknown, mode: AuthMode): DemoDataMode {
  const dataMode = value === undefined ? 'remote' : value;
  if (dataMode !== 'local' && dataMode !== 'remote') {
    throw new Error('VITE_DEMO_DATA_MODE must be local or remote');
  }
  if (mode !== 'demo' && dataMode === 'local') {
    throw new Error('VITE_DEMO_DATA_MODE local requires VITE_AUTH_MODE demo');
  }
  return dataMode;
}

export const authMode = parseAuthMode(import.meta.env.VITE_AUTH_MODE);
export const demoDataMode = parseDemoDataMode(import.meta.env.VITE_DEMO_DATA_MODE, authMode);
export const isLocalDemo = authMode === 'demo' && demoDataMode === 'local';
