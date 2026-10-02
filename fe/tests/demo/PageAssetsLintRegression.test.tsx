import { cleanup, render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import App from '../../src/App';
import baseline from '../fixtures/pageAssetsBaseline.json';
import { demoPageAssets } from '../../src/demo/demoPageAssets';

vi.mock('lottie-react', () => ({ default: () => null }));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it('demo landing preloads its unchanged assets without protected HTTP or all-page preload', async () => {
  const seen: string[] = [];
  const fetch = vi.fn(() => Promise.reject(new Error('Unexpected fetch')));
  vi.stubGlobal('Image', class {
    onload: (() => void) | null = null; onerror: (() => void) | null = null;
    set src(src: string) { seen.push(src); queueMicrotask(() => this.onload?.()); }
  });
  vi.stubGlobal('fetch', fetch);
  const client = new QueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>);
  await waitFor(() => expect(seen).toEqual(baseline.scrollLandingAssets));
  expect(fetch).not.toHaveBeenCalled();
  cleanup(); client.clear();
});
it.each([
  ['/chart', baseline.chartAssets], ['/mypage', baseline.mypageAssets],
  ['/userInfo', baseline.userInfoAssets], ['/toadAdvice', baseline.toadAdviceAssets],
  ['/missing', baseline.notFoundAssets], ['/pot/1', baseline.leakPotAssets], ['/pot', []],
])('preloads only route-specific existing assets for %s', (path, expected) => {
  expect(demoPageAssets(path as string)).toEqual(expected);
});
