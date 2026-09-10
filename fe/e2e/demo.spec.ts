import { test } from './fixtures';
import { expect, type Page, type BrowserContext, type Response } from '@playwright/test';
import { writeFileSync } from 'node:fs';

type Event = { order: number; method: string; path: string; status?: number; phase: string };
const auth = '/api/auth/demo/';
const origin = process.env.E2E_ORIGIN!;
const probe = `${new URL(origin).protocol}//127.0.0.1:${process.env.E2E_PROBE_PORT}`;
const secure = origin.startsWith('https:');
const headers = { 'Content-Type': 'application/json', 'X-MoneyToad-Demo': '1' };
let events: Event[] = [], external = 0, phase = 'initial';
function check(value: boolean, label: string): asserts value { if (!value) { writeFileSync(`${process.env.E2E_ARTIFACTS}/failed-check.json`, JSON.stringify({ check: label })); throw new Error(label); } }
function object(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null; }
function pathOf(url: string) { return new URL(url).pathname; }
const protectedPath = (path: string) => /^\/api\/(users|transactions|budgets|cards)(\/|$)/.test(path);
async function snapshot(): Promise<Record<string, number>> {
  const response = await fetch(`${process.env.E2E_CONTROL}/snapshot`);
  check(response.ok, 'control snapshot available');
  return response.json() as Promise<Record<string, number>>;
}
async function access(response: Response) {
  const body: unknown = await response.json();
  check(object(body) && typeof body.accessToken === 'string', 'access response contract');
  return body.accessToken;
}
async function expiry(response: Response) {
  const body: unknown = await response.json();
  check(object(body) && body.demo === true && typeof body.expiresAt === 'string', 'session contract');
  return Date.parse(body.expiresAt);
}
async function cookie(context: BrowserContext) {
  const values = await context.cookies();
  const value = values.find(row => row.name === 'demoRefreshToken');
  check(Boolean(value), 'refresh cookie exists');
  check(value!.httpOnly && value!.secure === secure && value!.sameSite === 'Lax' && value!.path === '/api/auth/demo', 'cookie attributes');
  return value!;
}
async function chooseMonth(page: Page, month: number) {
  const dots = page.locator('#screen1 .recharts-line-dots image');
  await expect(dots).toHaveCount(12);
  const target = dots.nth(month - 1);
  await target.scrollIntoViewIfNeeded();
  const box = await target.boundingBox();
  check(box !== null, 'month dot has real geometry');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  const active = page.locator('#screen1 .recharts-active-dot image');
  await expect(active).toBeVisible();
  const activeBox = await active.boundingBox();
  check(activeBox !== null && Math.abs(activeBox.x - box.x) < 1 && Math.abs(activeBox.y - box.y) < 1, 'active dot is intended month');
  // Recharts renders its clickable activeDot above the base dot on pointer hover.
  await page.locator('#screen1 .recharts-active-dot image').click();
  await expect(page.locator('.jp-total')).toContainText('908,000원');
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${process.env.E2E_ARTIFACTS}/${name}.png`,
    mask: [page.locator('header')], fullPage: false });
}
async function post(page: Page, url: string) {
  return page.evaluate(async ({ url, headers }) => {
    try { const r = await fetch(url, { method: 'POST', headers, credentials: 'include', body: '{}' }); return r.status; }
    catch { return 0; }
  }, { url, headers });
}

test.beforeEach(async ({ context }) => {
  events = []; external = 0; phase = 'initial';
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (![origin, probe].includes(url.origin)) { external++; await route.abort(); return; }
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
  if (info.status !== info.expectedStatus) await shot(page, 'failure-' + phase).catch(() => {});
  const state = await snapshot();
  writeFileSync(`${process.env.E2E_ARTIFACTS}/${secure ? 'https' : 'http'}-${phase}-network.json`,
    JSON.stringify({ events, externalAttempts: external, backendAttempts: state.outbound }, null, 2));
  check(external === 0 && state.outbound === 0, 'no external application request attempts');
  check(!events.some(e => e.path === '/api/transactions/peer'), 'no demo peer requests');
  } finally {
    // Preserve failure status/count; remove raw diagnostic payloads before automatic artifacts.
    for (const error of info.errors) {
      const line = error.stack?.match(/demo\.spec\.ts:(\d+):\d+/)?.[1];
      error.message = ''; error.errorContext = undefined;
      error.stack = line ? `at demo.spec.ts:${line}:1` : undefined;
    }
  }
});

test('real demo visit: login, Chart, reload and revoke', async ({ page, context }) => {
  const initial = page.waitForResponse(r => pathOf(r.url()) === `${auth}reissue`);
  await page.goto('/');
  check((await initial).status() === 401, 'initial restore unauthorized');
  const start = page.getByRole('button', { name: '샘플 데이터로 체험하기', exact: true });
  await expect(start).toBeEnabled();
  check(events.filter(e => e.path === `${auth}login`).length === 0, 'no automatic login');
  const before = await snapshot();
  const logged = page.waitForResponse(r => pathOf(r.url()) === `${auth}login`);
  const session = page.waitForResponse(r => pathOf(r.url()) === `${auth}session` && r.status() === 200);
  const yearResponse = page.waitForResponse(r => pathOf(r.url()) === '/api/transactions' && r.status() === 200);
  phase = 'login';
  await start.click();
  const login = await logged;
  check(login.status() === 201, 'actual login created');
  let oldAccess = await access(login);
  const absolute = await expiry(await session);
  const requestHeaders = await login.request().allHeaders();
  check(login.request().postData() === '{}' && requestHeaders.origin === origin &&
    requestHeaders['x-moneytoad-demo'] === '1' && requestHeaders['content-type'].startsWith('application/json'), 'browser login request');
  const setCookie = (await login.headersArray()).find(h => h.name.toLowerCase() === 'set-cookie')?.value ?? '';
  check(!/;\s*domain=/i.test(setCookie) && /;\s*httponly/i.test(setCookie), 'host only HttpOnly cookie');
  const stored = await cookie(context);
  check(stored.expires * 1000 <= absolute && stored.expires * 1000 > Date.now(), 'cookie absolute expiry');
  await expect(page).toHaveURL(/\/chart$/);
  const seeded = await snapshot();
  check(seeded.users - before.users === 1 && seeded.cards - before.cards === 1 &&
    seeded.transactions - before.transactions === 240 && seeded.budgets - before.budgets === 72 &&
    seeded.financial === 0 && seeded.jobs === 0 && seeded.sessions === 1, 'real atomic seed counts');
  writeFileSync(`${process.env.E2E_ARTIFACTS}/seed-counts.json`, JSON.stringify(seeded, null, 2));
  const years: unknown = await (await yearResponse).json();
  check(Array.isArray(years) && years.length === 12, 'twelve API periods');
  const latest: unknown = years.at(-1);
  check(object(latest) && typeof latest.date === 'string' && latest.totalAmount === 908000 && latest.leaked === true, 'anchor API before');
  const month = Number(latest.date.split('-')[1]);
  if (secure) {
    await chooseMonth(page, month);
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 18,000원');
    const row = page.getByRole('row').filter({ hasText: '합성 장보기(분류 연습)' });
    await expect(row).toHaveCount(1);
    await shot(page, 'chart-before');
    const changed = page.waitForResponse(r => r.request().method() === 'PATCH' && /\/transactions\/\d+\/category$/.test(pathOf(r.url())));
    const refreshed = page.waitForResponse(r => pathOf(r.url()) === '/api/transactions' && r.status() === 200);
    phase = 'patch';
    await row.getByRole('combobox').click();
    await page.getByRole('option', { name: '마트 / 편의점', exact: true }).click();
    check((await changed).status() === 200, 'actual category PATCH');
    const afterYear: unknown = await (await refreshed).json();
    check(Array.isArray(afterYear) && afterYear.some((r: unknown) => object(r) && r.date === latest.date && r.leaked === false && r.totalAmount === 908000), 'annual leak recalculated');
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원');
    await expect(page.locator('.jp-total')).toContainText('908,000원');
    const changedDb = await snapshot();
    check(changedDb.changed === 1, 'actual SQL category changed');
    writeFileSync(`${process.env.E2E_ARTIFACTS}/chart-contract.json`, JSON.stringify({
      before: { month: latest.date, total: latest.totalAmount, leaked: latest.leaked },
      after: afterYear.filter((r: unknown) => object(r) && r.date === latest.date),
      dbAfter: changedDb,
      displayedLeakBefore: 18000, displayedLeakAfter: 0, displayedTotalAfter: 908000,
    }, null, 2));
    const patchDone = events.find(e => e.phase === 'patch' && e.method === 'PATCH' && e.status === 200)!.order;
    check(events.some(e => e.order > patchDone && e.method === 'GET' && e.status === 200 && /\/transactions\/:n\/:n$/.test(e.path)) &&
      events.some(e => e.order > patchDone && e.method === 'GET' && e.status === 200 && e.path.endsWith('/categories')), 'post PATCH GETs');
    await shot(page, 'chart-after');
  }
  // Path-matched inert document tests HttpOnly, avoiding /chart's cookie Path false positive.
  await page.evaluate(() => { const f = document.createElement('iframe'); f.id = 'cookie-probe'; f.src = '/api/auth/demo/__e2e_probe.html'; document.body.append(f); });
  const frame = page.frameLocator('#cookie-probe');
  await expect.poll(() => frame.locator('html').evaluate(() => document.title)).toBe('E2E inert probe');
  check(await frame.locator('html').evaluate(() => !document.cookie.split(';').some(v => v.trim().startsWith('demoRefreshToken='))), 'HttpOnly not visible at matching Path');
  await page.locator('#cookie-probe').evaluate(e => e.remove());
  const storageClear = await page.evaluate(() => !Object.values(localStorage).concat(Object.values(sessionStorage)).some(v => /eyJ[\w-]+\.[\w-]+\.[\w-]+/.test(String(v))));
  check(storageClear, 'demo JWT absent from browser storage');

  phase = 'restore-held';
  let releaseReissue: () => void = () => {};
  let releaseSession: () => void = () => {};
  let sawReissue: () => void = () => {};
  let sawSession: () => void = () => {};
  const rSeen = new Promise<void>(resolve => { sawReissue = resolve; });
  const sSeen = new Promise<void>(resolve => { sawSession = resolve; });
  const rGate = new Promise<void>(resolve => { releaseReissue = resolve; });
  const sGate = new Promise<void>(resolve => { releaseSession = resolve; });
  await page.route(`${origin}${auth}reissue`, async route => { sawReissue(); await rGate; await route.continue(); });
  await page.route(`${origin}${auth}session`, async route => { sawSession(); await sGate; await route.continue(); });
  const restoredToken = page.waitForResponse(r => pathOf(r.url()) === `${auth}reissue` && r.status() === 200);
  const restoredSession = page.waitForResponse(r => pathOf(r.url()) === `${auth}session` && r.status() === 200);
  const reload = page.reload();
  try {
    await rSeen;
    await expect(page.getByText('체험 연결을 확인하고 있습니다.', { exact: true })).toBeVisible();
    await expect(page.locator('#screen1')).toHaveCount(0);
    check(!events.some(e => e.phase === 'restore-held' && protectedPath(e.path)), 'no protected requests while reissue held');
    releaseReissue();
    await sSeen;
    await expect(page.locator('#screen1')).toHaveCount(0);
    check(!events.some(e => e.phase === 'restore-held' && protectedPath(e.path)), 'no protected requests while session held');
    phase = 'restored'; releaseSession(); await reload;
  } finally { releaseReissue(); releaseSession(); }
  const reissued = await restoredToken;
  oldAccess = await access(reissued);
  check((await reissued.request().allHeaders()).cookie?.includes(`demoRefreshToken=${stored.value}`) === true, 'actual refresh cookie sent');
  check(await expiry(await restoredSession) === absolute, 'absolute expiry unchanged');
  check((await cookie(context)).expires * 1000 <= absolute, 'rotated cookie expiry bounded');
  await page.unroute(`${origin}${auth}reissue`); await page.unroute(`${origin}${auth}session`);
  await expect(page.locator('#screen1')).toBeVisible();
  check(events.filter(e => e.path === `${auth}login` && e.status === undefined).length === 1, 'reload never logs in');
  if (secure) {
    await chooseMonth(page, month);
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원');
    await expect(page.getByRole('row').filter({ hasText: '합성 장보기(분류 연습)' }).getByRole('combobox')).toContainText('마트 / 편의점');
    await shot(page, 'chart-restored');
    // The existing detail screen intentionally covers the header; close it as a user would.
    await page.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(page.locator('#screen2')).toHaveCount(0);
  }
  phase = 'logout';
  const loggedOut = page.waitForResponse(r => pathOf(r.url()) === `${auth}logout`);
  await page.getByRole('button', { name: '체험 종료', exact: true }).click();
  check((await loggedOut).status() === 204, 'actual logout');
  await expect(start).toBeEnabled();
  check(!(await context.cookies()).some(c => c.name === 'demoRefreshToken'), 'cookie removed');
  check((await snapshot()).sessions === 0, 'Redis session removed');
  // Bearer exists only in this test process memory; no request/response dump is retained.
  const denied = await page.evaluate(async token => (await fetch('/api/transactions', {
    headers: { Authorization: `Bearer ${token}` },
  })).status, oldAccess);
  oldAccess = '';
  check(denied === 401, 'revoked AT rejected by real protected API');
  await page.goto('/chart');
  await expect(start).toBeEnabled();
  await expect(page.locator('#screen1')).toHaveCount(0);
  check(events.filter(e => e.path === `${auth}login` && e.status === undefined).length === 1, 'no new automatic login');
  await shot(page, 'logout');
  writeFileSync(`${process.env.E2E_ARTIFACTS}/cookie-contract.json`, JSON.stringify({
    name: stored.name, httpOnly: stored.httpOnly, secure: stored.secure, sameSite: stored.sameSite,
    path: stored.path, domainAttributeAbsent: true, sentOnReissue: true, jsVisible: false,
    absoluteExpiryUnchanged: true, removedOnLogout: true,
  }, null, 2));
});

test('separate browser CORS allow and reject', async ({ page, context }) => {
  phase = 'cors';
  // Playwright routing synthesizes preflight 204. Raw CDP only CONTINUES/FAILS requests;
  // the actual browser must send OPTIONS to the real Spring CorsFilter.
  await context.unrouteAll();
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.enable');
  cdp.on('Network.loadingFailed', (event: { errorText: string; corsErrorStatus?: { corsError: string } }) => {
    writeFileSync(`${process.env.E2E_ARTIFACTS}/cors-error.json`, JSON.stringify({
      error: event.errorText.match(/net::ERR_[A-Z_]+/)?.[0],
      cors: event.corsErrorStatus?.corsError.match(/^[A-Za-z]+$/)?.[0],
    }));
  });
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] });
  cdp.on('Fetch.requestPaused', async (event: { requestId: string; request: { url: string } }) => {
    if ([origin, probe].includes(new URL(event.request.url).origin)) {
      await cdp.send('Fetch.continueRequest', { requestId: event.requestId });
    } else {
      external++;
      await cdp.send('Fetch.failRequest', { requestId: event.requestId, errorReason: 'BlockedByClient' });
    }
  });
  await page.goto('/__e2e_probe.html');
  const before = await snapshot();
  check(await post(page, `${probe}${auth}login`) === 201, 'allowed cross origin actual login response readable');
  const afterAllowed = await snapshot();
  check(afterAllowed.options > before.options && afterAllowed.loginPosts === before.loginPosts + 1, 'allowed real preflight and POST');
  await page.goto(`${probe}/__e2e_probe.html`);
  check(await post(page, `${origin}${auth}login`) === 0, 'unconfigured origin browser rejects');
  const afterDenied = await snapshot();
  check(afterDenied.options > afterAllowed.options && afterDenied.optionsRejected > afterAllowed.optionsRejected && afterDenied.loginPosts === afterAllowed.loginPosts, 'denied preflight prevents POST');
  writeFileSync(`${process.env.E2E_ARTIFACTS}/cors-contract.json`, JSON.stringify({
    before, afterAllowed, afterDenied, allowedStatus: 201, deniedPreflightStatus: 403,
    deniedActualPostCount: afterDenied.loginPosts - afterAllowed.loginPosts,
  }, null, 2));
});
