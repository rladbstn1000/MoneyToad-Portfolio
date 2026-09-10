import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import App from '../src/App';
import * as assets from '../src/assets/pageAssets';
import baseline from './fixtures/pageAssetsBaseline.json';
import JPSelect from '../src/components/JPSelect';

vi.mock('lottie-react', () => ({ default: () => null }));

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it('preserves all seven asset arrays including order and derived values', () => {
  for (const name of ['scrollLandingAssets', 'userInfoAssets', 'leakPotAssets', 'mypageAssets', 'notFoundAssets', 'toadAdviceAssets', 'chartAssets'] as const) {
    expect(assets[name]).toEqual(baseline[name]);
  }
});
it('OAuth App still preloads the original full list in order', async () => {
  const seen: string[] = [];
  vi.stubGlobal('Image', class {
    onload: (() => void) | null = null; onerror: (() => void) | null = null;
    set src(src: string) { seen.push(src); queueMicrotask(() => this.onload?.()); }
  });
  vi.stubGlobal('fetch', (src: string) => { seen.push(src); return Promise.resolve(new Response('{}')); });
  const client = new QueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>);
  await waitFor(() => expect(seen).toEqual(Object.values(baseline).flat()));
  cleanup(); client.clear();
});
it('preserves JPSelect custom color and disabled behavior without a cast', () => {
  const onChange = vi.fn();
  const view = render(<JPSelect value="식비" onChange={onChange} options={[{ value: '식비', label: '식비' }]} colorMap={{ 식비: '#123456' }} disabled />);
  expect(screen.getByRole('combobox')).toHaveStyle({ '--jp-chip-color': '#123456' });
  expect(screen.getByRole('combobox')).toBeDisabled();
  expect(onChange).not.toHaveBeenCalled();
  view.rerender(<JPSelect value="식비" onChange={onChange} options={[{ value: '식비', label: '식비' }]} />);
  expect(screen.getByRole('combobox').style.getPropertyValue('--jp-chip-color')).toBe('');
});
