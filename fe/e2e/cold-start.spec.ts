import { clickDemoMenu, gotoDemoChart } from './demoNavigation';
import { test } from './fixtures';
import { expect } from '@playwright/test';
import { writeFileSync } from 'node:fs';

const origin = process.env.E2E_ORIGIN!;
const auth = '/api/auth/demo/';
type Event = { order: number; method: string; path: string; status?: number; phase: string };
const pathOf = (url: string) => new URL(url).pathname;
let events: Event[] = [], external = 0, phase = 'initial';
function check(value: boolean, label: string): asserts value {
  if (!value) {
    writeFileSync(`${process.env.E2E_ARTIFACTS}/cold-failed-check.json`, JSON.stringify({ check: label }));
    throw new Error(label);
  }
}
const requests = (method: string, path: string) => events.filter(event => event.method === method && event.path === path && event.status === undefined).length;
const posts = () => events.filter(event => event.method === 'POST' && event.status === undefined).length;
async function snapshot(): Promise<Record<string, number>> {
  const response = await fetch(`${process.env.E2E_CONTROL}/snapshot`);
  check(response.ok, 'owned snapshot available');
  return response.json() as Promise<Record<string, number>>;
}

test.beforeEach(async ({ context }) => {
  events = []; external = 0; phase = 'initial';
  await context.route('**/*', async route => {
    if (new URL(route.request().url()).origin !== origin) { external++; await route.abort(); return; }
    await route.continue();
  });
  await context.routeWebSocket(/.*/, socket => { external++; socket.close(); });
  context.on('request', request => {
    const path = pathOf(request.url());
    if (path.startsWith('/api/')) events.push({ order: events.length, method: request.method(),
      path: path.replace(/\/\d+(?=\/|$)/g, '/:n'), phase });
  });
  context.on('response', response => {
    const path = pathOf(response.url());
    if (path.startsWith('/api/')) events.push({ order: events.length, method: response.request().method(),
      path: path.replace(/\/\d+(?=\/|$)/g, '/:n'), status: response.status(), phase });
  });
});

test.afterEach(async ({ page }, info) => {
  try {
    const state = await snapshot();
    writeFileSync(`${process.env.E2E_ARTIFACTS}/cold-network.json`, JSON.stringify({ events,
      externalAttempts: external, backendAttempts: state.outbound, viewport: page.viewportSize() }, null, 2));
    check(external === 0 && state.outbound === 0, 'external application attempts zero');
    check(!events.some(event => event.path === '/api/transactions/peer'), 'demo peer requests zero');
  } finally {
    for (const error of info.errors) {
      const line = error.stack?.match(/cold-start\.spec\.ts:(\d+):\d+/)?.[1];
      error.message = ''; error.errorContext = undefined;
      error.stack = line ? `at cold-start.spec.ts:${line}:1` : undefined;
    }
  }
});

test('cold deadline stops polling; manual readiness never creates a visitor', async ({ page, context }) => {
  await page.clock.install();
  const initial = page.waitForResponse(response => pathOf(response.url()) === `${auth}ready` && response.status() === 503);
  await page.goto('/'); await initial;
  await expect(page.getByText('데모 서버 시작 중입니다. 처음 연결할 때 잠시 걸릴 수 있습니다.', { exact: true })).toBeVisible();
  await page.clock.runFor(0);
  // Only the browser clock advances. Product 240s/80-attempt/10s constants and
  // actual Spring/MySQL/Redis clocks remain unchanged.
  await page.clock.fastForward(240_000);
  const manual = page.getByRole('button', { name: '서버 다시 확인', exact: true });
  await expect(page.getByRole('heading', { name: '서버를 시작하고 있습니다', exact: true })).toBeVisible();
  await expect(manual).toBeEnabled();
  check(posts() === 0, 'deadline automatic POST zero');
  const atDeadline = requests('GET', `${auth}ready`);
  check(atDeadline >= 1 && atDeadline <= 80, 'initial readiness bounded');
  phase = 'after-deadline';
  await page.clock.fastForward(600_000);
  // A real event-loop round trip lets any accidentally dispatched network work
  // settle; this is not a readiness retry or an API response replacement.
  await page.evaluate(() => Promise.resolve());
  check(requests('GET', `${auth}ready`) === atDeadline && posts() === 0, 'deadline stops automatic traffic');
  const panel = page.getByRole('region', { name: '체험 인증', exact: true });
  const layout = await panel.evaluate(element => {
    const all = [element, ...element.querySelectorAll('h2, p, button')];
    const boxes = all.map(item => item.getBoundingClientRect());
    return { width: innerWidth, height: innerHeight,
      bodyOverflow: Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) - innerWidth,
      readable: boxes.every(box => box.left >= -1 && box.right <= innerWidth + 1 && box.top >= -1 && box.bottom <= innerHeight + 1),
      buttonHeight: element.querySelector('button')!.getBoundingClientRect().height };
  });
  check(layout.width === 390 && layout.bodyOverflow <= 1 && layout.readable && layout.buttonHeight >= 44,
    '390 recovery message and button readable');
  await panel.screenshot({ path: `${process.env.E2E_ARTIFACTS}/cold-recovery-390.png` });
  const countsBefore = await snapshot();
  check(countsBefore.users === 0 && countsBefore.sessions === 0, 'cold readiness creates no visitor');
  // Test-process-only control toggles the server fixture. Browsers never see or
  // call this control; subsequent readiness is forwarded to the actual backend.
  const released = await fetch(`${process.env.E2E_CONTROL}/cold-ready`, { method: 'POST' });
  check(released.status === 204, 'owned server fixture released');
  phase = 'manual';
  const ready = page.waitForResponse(response => pathOf(response.url()) === `${auth}ready` && response.status() === 200);
  await manual.click(); await ready;
  const start = page.getByRole('button', { name: '샘플 데이터로 체험하기', exact: true });
  await expect(start).toBeEnabled();
  check(requests('GET', `${auth}ready`) === atDeadline + 1, 'manual readiness exactly one GET');
  check(posts() === 0, 'manual success POST zero');
  await page.clock.fastForward(60_000);
  await page.evaluate(() => Promise.resolve());
  check(requests('GET', `${auth}ready`) === atDeadline + 1 && posts() === 0, 'manual ready never schedules automatic traffic');
  await page.clock.resume();
  phase = 'explicit-login';
  const login = page.waitForResponse(response => pathOf(response.url()) === `${auth}login`);
  const session = page.waitForResponse(response => pathOf(response.url()) === `${auth}session` && response.status() === 200);
  const annual = page.waitForResponse(response => pathOf(response.url()) === '/api/transactions' && response.status() === 200);
  await start.click();
  check((await login).status() === 201, 'explicit real login created');
  await session; await annual;
  await gotoDemoChart(page);
  await expect(page.locator('#screen1')).toBeVisible();
  check(requests('POST', `${auth}login`) === 1, 'one explicit login');
  const seeded = await snapshot();
  check(seeded.users === 1 && seeded.cards === 1 && seeded.transactions === 240 && seeded.budgets === 72
    && seeded.financial === 0 && seeded.jobs === 0 && seeded.sessions === 1, 'real isolated seed and session');
  phase = 'logout';
  const logout = page.waitForResponse(response => pathOf(response.url()) === `${auth}logout`);
  await clickDemoMenu(page, '체험 종료');
  check((await logout).status() === 204, 'real logout');
  await expect(start).toBeEnabled();
  check(!(await context.cookies()).some(value => value.name === 'demoRefreshToken'), 'refresh cookie removed');
  check((await snapshot()).sessions === 0, 'Redis session removed');
  await page.goto('/chart'); await expect(start).toBeEnabled();
  check(requests('POST', `${auth}login`) === 1, 'no automatic visitor after logout');
  const contract = { browserClockAdvance: true, productionTimingChanged: false,
    initialReadyFixture: 'server-side exact-path 503; no browser API mock', manualReadyBackend: 'real Spring',
    initialReadyRequests: atDeadline, automaticReadyAfterDeadline: 0, manualReadyRequests: 1,
    automaticPostsAfterManual: 0, explicitLoginPosts: 1, chartReached: true, logoutConfirmed: true,
    externalAttempts: external, layout };
  writeFileSync(`${process.env.E2E_ARTIFACTS}/cold-contract.json`, JSON.stringify(contract, null, 2));
});
