import { cleanup, render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import App from '../../src/App';
import baseline from '../fixtures/pageAssetsBaseline.json';

vi.mock('lottie-react', () => ({ default: () => null }));

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it('demo App preloads only the unchanged landing/chart subset with no auth HTTP', async () => {
  const seen: string[] = [];
  const fetch = vi.fn(() => Promise.reject(new Error('Unexpected fetch')));
  vi.stubGlobal('Image', class {
    onload: (() => void) | null = null; onerror: (() => void) | null = null;
    set src(src: string) { seen.push(src); queueMicrotask(() => this.onload?.()); }
  });
  vi.stubGlobal('fetch', fetch);
  const client = new QueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>);
  await waitFor(() => expect(seen).toEqual([...baseline.scrollLandingAssets, ...baseline.chartAssets]));
  expect(fetch).not.toHaveBeenCalled();
  cleanup(); client.clear();
});
