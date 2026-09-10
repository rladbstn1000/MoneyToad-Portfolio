import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { authMode } from './authMode';
import { bootstrapDemo, expireDemo } from './demoCoordinator';
import { useAuthStore } from '../store/authStore';
import { getDemoQueryClient } from './demoQueryClient';

const oauthClient = new QueryClient();

export default function AuthProvider({ children }: { children: ReactNode }) {
  const generation = useAuthStore(state => state.generation);
  const expiresAt = useAuthStore(state => state.expiresAt);
  useEffect(() => {
    if (authMode !== 'demo') return;
    void bootstrapDemo().catch(() => {});
  }, []);
  useEffect(() => {
    if (authMode !== 'demo' || expiresAt === null) return;
    const timeout = window.setTimeout(expireDemo, Math.max(0, expiresAt - Date.now()));
    window.addEventListener('focus', expireDemo);
    window.addEventListener('pageshow', expireDemo);
    document.addEventListener('visibilitychange', expireDemo);
    return () => {
      window.clearTimeout(timeout);
      window.removeEventListener('focus', expireDemo);
      window.removeEventListener('pageshow', expireDemo);
      document.removeEventListener('visibilitychange', expireDemo);
    };
  }, [expiresAt]);
  return <QueryClientProvider key={authMode === 'demo' ? generation : 'oauth'}
    client={authMode === 'demo' ? getDemoQueryClient() : oauthClient}>{children}</QueryClientProvider>;
}
