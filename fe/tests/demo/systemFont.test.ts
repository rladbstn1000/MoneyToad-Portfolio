import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Vitest stubs CSS imports by default, including ?raw. Inspect the product source
// here; the deployment runner separately checks the real compiled CSS assets.
const directory = dirname(fileURLToPath(import.meta.url));
const globalCss = readFileSync(resolve(directory, '../../src/index.css'), 'utf8');
const landingCss = readFileSync(resolve(directory, '../../src/pages/ScrollLandingPage.css'), 'utf8');
const budgetCss = readFileSync(resolve(directory, '../../src/pages/LeakPotPage.css'), 'utf8');

describe('actual product system fonts', () => {
  it.each([globalCss, landingCss, budgetCss])('uses no font-face or remote font URL (%#)', css => {
    expect(css).not.toMatch(/@font-face|https?:\/\/|\.woff2?\b|Joseon100Years/i);
    expect(css).toContain('font-family: var(--app-font)');
  });
  it('defines a local system/Korean fallback stack without downloaded assets', () => {
    expect(globalCss).toContain('--app-font: system-ui');
    expect(globalCss).toContain('"Apple SD Gothic Neo", "Malgun Gothic", sans-serif');
  });
});
