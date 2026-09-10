import { QueryClient } from '@tanstack/react-query';
import { useAuthStore } from '../store/authStore';
import { authMode } from './authMode';

const makeClient = () => new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
let client = makeClient();
if (authMode === 'demo') useAuthStore.subscribe((state, previous) => {
  if (state.generation !== previous.generation) {
    const oldClient = client;
    client = makeClient();
    void oldClient.cancelQueries();
    oldClient.clear();
  }
});
export function getDemoQueryClient(): QueryClient { return client; }
