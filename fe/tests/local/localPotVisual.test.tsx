import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PotVisualization } from '../../src/pages/LeakPotPage';
import { getLocalPotLeakGeometry, getPotLeakGeometry } from '../../src/pages/potLeakAnchors';

vi.mock('lottie-react', () => ({ default: () => <span data-testid="local-water-instance" /> }));
beforeEach(() => vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } }))));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const formatter = new Intl.NumberFormat('ko-KR');
const row = (name: string, spending: number, threshold: number, id: number | null = 1) => ({
  name, spending, threshold, id, initialBudget: threshold,
});
const node = (container: HTMLElement, category: string, selector = '.crack') =>
  [...container.querySelectorAll(selector)].find(element => element.getAttribute('data-category') === category);

describe('local pot severity over the complete editable spending range', () => {
  it.each([500000, 58000])('maps spending %i through all five positive intervals and back without early saturation', spending => {
    const ratios = [0, .2, .4, .6, .8, 1];
    const forward = ratios.map(ratio => getLocalPotLeakGeometry('카페', spending, spending * (1 - ratio)));
    expect(forward[0].width).toBe(0); expect(forward[0].waterScale).toBe(0);
    for (let index = 1; index < forward.length; index++) {
      const state = forward[index];
      expect(state.ratio).toBeCloseTo(ratios[index]);
      expect(state.crackScale).toBeCloseTo(.4 + 1.1 * ratios[index]);
      expect(state.waterScale).toBeCloseTo(.3 + 1.7 * ratios[index]);
      expect(state.width).toBeGreaterThan(forward[index - 1].width);
      expect(state.waterScale).toBeGreaterThan(forward[index - 1].waterScale);
      expect([state.x, state.y]).toEqual([forward[0].x, forward[0].y]);
    }
    const reverse = [...ratios].reverse().map(ratio => getLocalPotLeakGeometry('카페', spending, spending * (1 - ratio)));
    expect(reverse).toEqual([...forward].reverse());
    expect(forward.at(-1)?.crackScale).toBe(1.5); expect(forward.at(-1)?.waterScale).toBe(2);
  });
  it('treats an absent budget and no spending as unavailable severity rather than a zero budget', () => {
    for (const [spending, budget] of [[500000, null], [0, 0], [500000, 500000], [500000, 600000]] as const) {
      const result = getLocalPotLeakGeometry('주거 / 통신', spending, budget);
      expect(result.ratio).toBe(0); expect(result.width).toBe(0); expect(result.waterScale).toBe(0);
    }
    expect(getLocalPotLeakGeometry('주거 / 통신', 500000, 0).ratio).toBe(1);
  });
  it('uses relative spending only locally and retains the remote absolute-amount curve', () => {
    const large = getLocalPotLeakGeometry('카페', 500000, 400000);
    const small = getLocalPotLeakGeometry('카페', 50000, 40000);
    expect(large).toEqual(small);
    expect(getPotLeakGeometry('카페', 88000).crackScale).toBe(1.5);
    expect(getPotLeakGeometry('카페', 136000).waterScale).toBe(2);
    expect(getLocalPotLeakGeometry('카페', 500000, 364000).waterScale).toBeLessThan(2);
  });
  it('preserves category anchors, origins and the live Lottie instance across positive updates', async () => {
    const display = (budget: number, food = true) => <PotVisualization formatter={formatter} totalLeak={500000 - budget}
      leakingCategories={[row('주거 / 통신', 500000, budget), ...(food ? [row('식비', 180000, 100000, 2)] : [])]} />;
    const view = render(display(400000));
    await waitFor(() => expect(view.container.querySelectorAll('[data-testid="local-water-instance"]')).toHaveLength(2));
    const stream = node(view.container, '주거 / 통신', '.pot-leak-stream');
    const animation = stream?.querySelector('[data-testid="local-water-instance"]');
    const crack = node(view.container, '주거 / 통신');
    const origins = ['data-origin-x', 'data-origin-y', 'data-anchor-u', 'data-anchor-v'];
    const original = origins.map(name => crack?.getAttribute(name));
    let width = Number(crack?.getAttribute('width'));
    for (const budget of [300000, 200000, 100000, 0]) {
      view.rerender(display(budget, false));
      const current = node(view.container, '주거 / 통신');
      expect(origins.map(name => current?.getAttribute(name))).toEqual(original);
      expect(origins.map(name => stream?.getAttribute(name))).toEqual(original);
      expect(node(view.container, '주거 / 통신', '.pot-leak-stream')).toBe(stream);
      expect(stream?.querySelector('[data-testid="local-water-instance"]')).toBe(animation);
      expect(Number(current?.getAttribute('width'))).toBeGreaterThan(width);
      width = Number(current?.getAttribute('width'));
    }
    view.rerender(display(500000, false));
    expect(view.container.querySelector('.crack')).toBeNull();
    expect(view.container.querySelector('.pot-leak-stream')).toBeNull();
    view.rerender(display(400000, false));
    expect(origins.map(name => node(view.container, '주거 / 통신')?.getAttribute(name))).toEqual(original);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('/leakPot/water.json');
  });
  it('does not render a missing budget as a full leak when its spending is positive', () => {
    const view = render(<PotVisualization formatter={formatter} totalLeak={0}
      leakingCategories={[row('기타', 100000, 0, null)]} />);
    expect(view.container.querySelector('.crack')).toBeNull();
    expect(view.container.querySelector('.pot-leak-stream')).toBeNull();
  });
});
