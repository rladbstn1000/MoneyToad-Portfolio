import { QueryClient } from '@tanstack/react-query';
import { useLocalDemoStore } from './localDemoStore';

const makeClient = () => new QueryClient({ defaultOptions: {
  queries: { retry: false }, mutations: { retry: false },
} });
let client = makeClient();
useLocalDemoStore.subscribe((state, previous) => {
  if (state.generation === previous.generation) return;
  const oldClient = client;
  client = makeClient();
  void oldClient.cancelQueries();
  oldClient.clear();
});
export function getLocalDemoQueryClient(): QueryClient { return client; }
