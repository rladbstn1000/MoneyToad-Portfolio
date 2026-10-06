import { describe, expect, it } from 'vitest';
import { DEMO_BUDGET_CATEGORIES } from '../src/demo/demoBudgetPresentation';
import { getPotLeakAnchor, getPotLeakGeometry, getPotLeakSeverity, POT_IMAGE_FRAME, POT_LEAK_ANCHORS } from '../src/pages/potLeakAnchors';

// Conservative visible-body boundary read from the native 280×319 image.
const contour = [[0.24, 0.14], [0.4, 0.06], [0.55, 0.08], [0.7, 0.13], [0.85, 0.2], [0.94, 0.28]];
function bodyInset(v: number) {
  const index = contour.findIndex(([height]) => height >= v);
  const [hiV, hiU] = contour[index];
  const [loV, loU] = contour[Math.max(0, index - 1)];
  return index === 0 ? hiU : loU + (hiU - loU) * (v - loV) / (hiV - loV);
}

describe('category-keyed pot body anchors', () => {
  it('covers every supported category once, independent of row or budget IDs', () => {
    expect(Object.keys(POT_LEAK_ANCHORS).sort()).toEqual([...DEMO_BUDGET_CATEGORIES].sort());
    expect(new Set(DEMO_BUDGET_CATEGORIES.map(name => JSON.stringify(getPotLeakAnchor(name)))).size).toBe(12);
    const before = DEMO_BUDGET_CATEGORIES.map(name => getPotLeakGeometry(name, 18000));
    [...DEMO_BUDGET_CATEGORIES].reverse().forEach(name => getPotLeakGeometry(name, 900000));
    expect(DEMO_BUDGET_CATEGORIES.map(name => getPotLeakGeometry(name, 18000))).toEqual(before);
  });
  // Stage 27 replaces full decorative-rectangle separation with visible dark
  // core separation. Maximum-size fracture lines may cross, as in the original
  // artwork; they must not force ordinary severity back to tiny point effects.
  it.each([1, 3, 5, 12])('keeps %i maximum-severity dark hole cores distinct and on the body', count => {
    const holes = DEMO_BUDGET_CATEGORIES.slice(0, count).map(name => getPotLeakGeometry(name, Number.MAX_SAFE_INTEGER));
    for (const hole of holes) {
      // Conservative central dark area read from broken.webp (565 × 442).
      const rx = hole.width * 0.18, ry = hole.height * 0.20;
      const left = (hole.x - rx - POT_IMAGE_FRAME.x) / POT_IMAGE_FRAME.width;
      const right = (hole.x + rx - POT_IMAGE_FRAME.x) / POT_IMAGE_FRAME.width;
      const top = (hole.y - ry - POT_IMAGE_FRAME.y) / POT_IMAGE_FRAME.height;
      const bottom = (hole.y + ry - POT_IMAGE_FRAME.y) / POT_IMAGE_FRAME.height;
      expect(top).toBeGreaterThan(0.24); expect(bottom).toBeLessThan(0.94);
      expect(left).toBeGreaterThan(Math.max(bodyInset(top), bodyInset(bottom)));
      expect(right).toBeLessThan(1 - Math.max(bodyInset(top), bodyInset(bottom)));
      for (const other of holes.filter(value => value !== hole)) {
        const separateX = Math.abs(hole.x - other.x) >= (hole.width + other.width) * 0.18;
        const separateY = Math.abs(hole.y - other.y) >= (hole.height + other.height) * 0.20;
        expect(separateX || separateY).toBe(true);
      }
    }
  });
  it('keeps the hole origin fixed while amount changes affect only bounded size', () => {
    for (const name of DEMO_BUDGET_CATEGORIES) {
      const small = getPotLeakGeometry(name, 1), large = getPotLeakGeometry(name, Number.MAX_SAFE_INTEGER);
      expect([small.x, small.y]).toEqual([large.x, large.y]);
      expect(large.width).toBeGreaterThan(small.width); expect(large.width).toBeLessThanOrEqual(117);
      expect((large.x - large.left) / large.width).toBeCloseTo(0.52);
      expect((large.y - large.top) / large.height).toBeCloseTo(0.57);
    }
  });
  it('restores both original category-local curves with explicit bounded saturation', () => {
    const amounts = [0, 10000, 30000, 60000, 120000, 300000];
    const states = amounts.map(amount => getPotLeakGeometry('카페', amount));
    expect(states[0].width).toBe(0); expect(states[0].waterScale).toBe(0);
    expect(states.map(state => state.crackScale)).toEqual([0, 0.525, 0.775, 1.15, 1.5, 1.5]);
    expect(states.map(state => state.waterScale)).toEqual([0, 0.425, 0.675, 1.05, 1.8, 2]);
    for (let index = 1; index < states.length; index++) {
      expect([states[index].x, states[index].y]).toEqual([states[0].x, states[0].y]);
      expect(states[index].width).toBeGreaterThanOrEqual(states[index - 1].width);
      expect(states[index].waterScale).toBeGreaterThan(states[index - 1].waterScale);
      if (index < 4) expect(states[index].width).toBeGreaterThan(states[index - 1].width);
    }
    expect(getPotLeakSeverity(88000).crackScale).toBe(1.5);
    expect(getPotLeakSeverity(136000).waterScale).toBe(2);
    expect(getPotLeakSeverity(-1)).toEqual({ crackScale: 0, waterScale: 0 });
    const cafe = getPotLeakGeometry('카페', 30000);
    getPotLeakGeometry('식비', 300000);
    expect(getPotLeakGeometry('카페', 30000)).toEqual(cafe);
  });
  it('uses unequal heights and interior points instead of an evenly spaced ring', () => {
    const points = Object.values(POT_LEAK_ANCHORS);
    expect(new Set(points.map(point => point.v)).size).toBe(points.length);
    const radii = points.map(point => Math.hypot(point.u - 0.5, point.v - 0.57));
    expect(Math.max(...radii) - Math.min(...radii)).toBeGreaterThan(0.15);
    expect(points.some(point => Math.abs(point.u - 0.5) < 0.06 && Math.abs(point.v - 0.57) < 0.15)).toBe(true);
  });
});
