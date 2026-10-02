import { test } from './fixtures';
import { expect, type Page, type Response } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { clickDemoMenu, gotoDemoChart } from './demoNavigation';
import { parsePublicDemoSetCookie } from './publicDemoCookie';

const origin = process.env.E2E_ORIGIN!;
const auth = '/api/auth/demo/';
type Event = { order: number; method: string; path: string; status?: number; phase: string };
let events: Event[] = [], external = 0, forbidden = 0, phase = 'initial';
let mutations: Record<string, number> = {};
const pathOf = (url: string) => new URL(url).pathname;
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;
const protectedPath = (path: string) => /^\/api\/(users|transactions|budgets|cards)(\/|$)/.test(path);
function check(value: boolean, label: string): asserts value {
  if (!value) { writeFileSync(`${process.env.E2E_ARTIFACTS}/full-failed-check.json`, JSON.stringify({ check: label, phase })); throw new Error(label); }
}
async function snapshot(): Promise<Record<string, number>> {
  const response = await fetch(`${process.env.E2E_CONTROL}/snapshot`);
  check(response.ok, 'owned aggregate snapshot available');
  return response.json() as Promise<Record<string, number>>;
}
async function deadline(response: Response) {
  const value: unknown = await response.json();
  check(object(value) && value.demo === true && typeof value.expiresAt === 'string', 'session shape');
  return Date.parse(value.expiresAt);
}
async function noOverflow(page: Page, pageName: string) {
  check(await page.evaluate(() => Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) <= innerWidth + 1), `${pageName} document horizontal overflow zero`);
}
async function potGeometry(page: Page) {
  return page.locator('.demo-pot-stage').evaluate(stageElement => {
    const box = (element: Element) => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height };
    };
    const stage = box(stageElement);
    const navElement = document.querySelector('.demo-pot-page .month-navigation');
    const panelElement = document.querySelector('.demo-pot-page .control-panel');
    const potElement = stageElement.querySelector('#pot-body image');
    const characters = [...stageElement.querySelectorAll('.characters img')].map(element => ({ ...box(element), loaded: element instanceof HTMLImageElement && element.complete && element.naturalWidth > 0 }));
    const pot = potElement ? box(potElement) : null;
    const centerX = pot ? (pot.left + pot.right) / 2 : -1, centerY = pot ? (pot.top + pot.bottom) / 2 : -1;
    const hit = document.elementFromPoint(centerX, centerY);
    return { stage, nav: navElement ? box(navElement) : null, panel: panelElement ? box(panelElement) : null, pot, characters,
      viewportWidth: innerWidth, viewportHeight: innerHeight,
      potCenterVisible: centerX >= 0 && centerX <= innerWidth && centerY >= 0 && centerY <= innerHeight,
      potHitBelongsToStage: hit !== null && stageElement.contains(hit),
      potIntersectsViewport: pot !== null && pot.right > 0 && pot.left < innerWidth && pot.bottom > 0 && pot.top < innerHeight,
      charactersInsideStage: characters.every(character => character.left >= stage.left - 1 && character.right <= stage.right + 1 && character.top >= stage.top - 1 && character.bottom <= stage.bottom + 1) };
  });
}
async function chartGeometry(page: Page) {
  return page.evaluate(() => {
    const box = (element: Element) => {
      const rect = element.getBoundingClientRect(), style = getComputedStyle(element);
      return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height,
        clientWidth: element.clientWidth, scrollWidth: element.scrollWidth, visibleStyle: style.display !== 'none' && style.visibility === 'visible', opacity: Number(style.opacity) };
    };
    const one = (selector: string) => { const element = document.querySelector(selector); return element ? box(element) : null; };
    return { viewportWidth: innerWidth, viewportHeight: innerHeight,
      documentOverflow: Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) - innerWidth,
      overview: one('#screen1'), lineWrapper: one('.jp-linechart-wrap'),
      dots: [...document.querySelectorAll('#screen1 .recharts-line-dots image')].map(box),
      activeDots: [...document.querySelectorAll('#screen1 .recharts-active-dot image')].map(box),
      overlays: [...document.querySelectorAll('.loading-overlay')].map(box) };
  });
}
function errorCategories(errors: { message?: string; stack?: string }[]) {
  const combined = errors.map(error => `${error.message ?? ''}\n${error.stack ?? ''}`).join('\n');
  const categories: string[] = [];
  for (const [pattern, category] of [
    [/not attached|detached from|element.*detached/i, 'ELEMENT_DETACHED'],
    [/not visible|invisible|no layout object/i, 'ELEMENT_NOT_VISIBLE'],
    [/not stable/i, 'ELEMENT_NOT_STABLE'],
    [/strict mode violation/i, 'LOCATOR_NOT_UNIQUE'],
    [/Target.*closed|page.*closed|context.*closed/i, 'TARGET_CLOSED'],
    [/intercepts pointer|outside.*viewport/i, 'ACTION_OBSCURED'],
    [/Timeout|timed out/i, 'TIMEOUT'],
    [/expect\(|expect\./i, 'ASSERTION'],
  ] as const) if (pattern.test(combined)) categories.push(category);
  return categories.length ? categories : ['UNCLASSIFIED_SAFE_ERROR'];
}
async function chooseChartMonth(page: Page, month: number) {
  const mobile = page.getByRole('navigation', { name: '월별 상세 보기', exact: true });
  if (page.viewportSize()!.width <= 768) {
    await expect(mobile).toBeVisible();
    await mobile.getByRole('button', { name: new RegExp(`년 ${month}월 상세 보기$`) }).click();
  }
  else {
    const dots = page.locator('#screen1 .recharts-line-dots image');
    await expect(dots).toHaveCount(12); const dot = dots.nth(month - 1);
    // Refetched Recharts dots can be replaced between layout frames. Scroll the stable chart wrapper, then observe the current visible dot.
    await page.locator('.jp-linechart-wrap').scrollIntoViewIfNeeded();
    await expect(dot).toBeVisible(); await page.mouse.move(0, 0);
    const active = page.locator('#screen1 .recharts-active-dot image');
    await expect.poll(async () => {
      const box = await dot.boundingBox(); if (!box) return false;
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      const hovered = await active.boundingBox();
      return hovered !== null && Math.abs(hovered.x - box.x) < 1 && Math.abs(hovered.y - box.y) < 1;
    }).toBe(true);
    await active.click();
  }
  await expect(page.locator('.jp-total')).toContainText('908,000원');
}
async function openProfile(page: Page) {
  await page.getByRole('button', { name: '문 열고 들어가기', exact: true }).click();
  await page.getByRole('button', { name: '콩쥐 만나기', exact: true }).click();
  await page.getByRole('button', { name: '샘플 정보 보기', exact: true }).click();
  return page.getByRole('dialog', { name: '콩쥐의 정보 수정하기', exact: true });
}
async function shot(page: Page, label: string) {
  const width = page.viewportSize()!.width;
  await page.screenshot({ path: `${process.env.E2E_ARTIFACTS}/full-${width}-${label}.png`, fullPage: false,
    mask: [page.locator('header.app-header'), page.locator('.mp-demo-name')] });
}

// Continue only real owned-origin requests. API responses are never replaced.
test.beforeEach(async ({ context }) => {
  events = []; external = 0; forbidden = 0; phase = 'initial'; mutations = {};
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) { external++; await route.abort(); return; }
    if (url.pathname.startsWith('/api/')) {
      const path = url.pathname, method = request.method();
      const allowed = method === 'GET' && (/^\/api\/auth\/demo\/(ready|session)$/.test(path)
          || path === '/api/users' || /^\/api\/transactions(?:\/\d+\/\d+(?:\/categories)?)?$/.test(path)
          || /^\/api\/budgets\/\d+\/\d+$/.test(path))
        || method === 'POST' && /^\/api\/auth\/demo\/(login|reissue|logout)$/.test(path)
        || method === 'PATCH' && (path === '/api/budgets' || /^\/api\/transactions\/\d+\/category$/.test(path));
      if (!allowed) { forbidden++; await route.abort(); return; }
      if (method === 'POST' || method === 'PATCH') {
        const key = path.replace(/\/\d+(?=\/|$)/g, '/:n');
        mutations[key] = (mutations[key] ?? 0) + 1;
        const maximum = path === `${auth}reissue` ? 3 : path === '/api/budgets' ? 2 : 1;
        if (mutations[key] > maximum) { forbidden++; await route.abort(); return; }
      }
    }
    await route.continue();
  });
  await context.routeWebSocket(/.*/, socket => { external++; socket.close(); });
  context.on('request', request => {
    const path = pathOf(request.url());
    if (path.startsWith('/api/')) events.push({ order: events.length, method: request.method(), path: path.replace(/\/\d+(?=\/|$)/g, '/:n'), phase });
  });
  context.on('response', response => {
    const path = pathOf(response.url());
    if (path.startsWith('/api/')) events.push({ order: events.length, method: response.request().method(), path: path.replace(/\/\d+(?=\/|$)/g, '/:n'), status: response.status(), phase });
  });
});
test.afterEach(async ({ page }, info) => {
  if (info.status !== 'passed') {
    const line = info.errors[0]?.stack?.match(/full-experience\.spec\.ts:(\d+):\d+/)?.[1];
    const chart = phase === 'chart' ? await chartGeometry(page).catch(() => null) : null;
    writeFileSync(`${process.env.E2E_ARTIFACTS}/full-failure-location.json`, JSON.stringify({ phase, line: line ?? 'unknown', categories: errorCategories(info.errors), chart }));
    if (phase === 'chart') await shot(page, 'failed-chart').catch(() => {});
  }
  try {
    const state = await snapshot();
    writeFileSync(`${process.env.E2E_ARTIFACTS}/full-${page.viewportSize()!.width}-network.json`, JSON.stringify({ events, externalAttempts: external, forbiddenAttempts: forbidden, backendAttempts: state.outbound }));
    check(external === 0 && forbidden === 0 && state.outbound === 0, 'external AI peer cards and forbidden mutations zero');
  } finally {
    for (const error of info.errors) {
      const line = error.stack?.match(/full-experience\.spec\.ts:(\d+):\d+/)?.[1];
      error.message = ''; error.errorContext = undefined;
      error.stack = line ? `at full-experience.spec.ts:${line}:1` : undefined;
    }
  }
});

for (const viewport of [{ width: 390, height: 844 }, { width: 768, height: 1024 }, { width: 1440, height: 1000 }]) {
  test(`full original demo experience ${viewport.width}x${viewport.height}`, async ({ page, context }) => {
    await page.setViewportSize(viewport);
    const initial = page.waitForResponse(response => pathOf(response.url()) === `${auth}reissue`);
    await page.goto('/'); check((await initial).status() === 401, 'initial restore unauthorized');
    await noOverflow(page, 'landing');
    await page.getByRole('button', { name: 'Go to page 4', exact: true }).click();
    await expect(page.locator('.dk-counter .cur')).toHaveText('04');
    await expect(page.locator('.dk-content.show .dk-title')).toHaveText('콩쥐의 장독대');
    const start = page.getByRole('button', { name: '샘플 데이터로 체험하기', exact: true });
    await expect(start).toBeEnabled();
    check(!events.some(event => event.path === `${auth}login`), 'no automatic initial login');
    const before = await snapshot();
    const loginPromise = page.waitForResponse(response => pathOf(response.url()) === `${auth}login`);
    const sessionPromise = page.waitForResponse(response => pathOf(response.url()) === `${auth}session` && response.status() === 200);
    const annualPromise = page.waitForResponse(response => pathOf(response.url()) === '/api/transactions' && response.status() === 200);
    phase = 'login'; await start.click(); const login = await loginPromise;
    check(login.status() === 201, 'one real visitor created');
    const firstDeadline = await deadline(await sessionPromise);
    await parsePublicDemoSetCookie(login, { deployment: 'public-demo', operation: 'issue' });
    await expect(page).toHaveURL(/\/pot\/\d+$/);
    const years: unknown = await (await annualPromise).json();
    check(Array.isArray(years) && years.length === 12, 'real API twelve-month anchor');
    const latest: unknown = years.at(-1), historic: unknown = years.at(-6);
    check(object(latest) && typeof latest.date === 'string' && latest.totalAmount === 908000 && latest.leaked === true, 'anchor total and leak before');
    check(object(historic) && typeof historic.date === 'string', 'historic advice period');
    const month = Number(latest.date.split('-')[1]), historicMonth = Number(historic.date.split('-')[1]);
    const seeded = await snapshot();
    check(seeded.users === before.users + 1 && seeded.cards === before.cards + 1 && seeded.transactions === before.transactions + 240
      && seeded.budgets === before.budgets + 72 && seeded.financial === 0 && seeded.jobs === 0, 'same deterministic seed');

    phase = 'pot-original';
    await expect(page.locator('[data-demo-page="pot"]')).toBeVisible();
    await expect(page.getByText('총 18,000냥이 새고 있소!', { exact: true })).toBeVisible();
    await expect(page.locator('.crack')).toHaveCount(1);
    await expect(page.locator('#waters foreignObject')).toHaveCount(1);
    await expect(page.locator('.water-animation svg')).toHaveCount(1);
    await expect.poll(() => page.locator('#puddle-group').evaluate(element => Number(getComputedStyle(element).opacity))).toBeGreaterThan(0);
    await noOverflow(page, 'pot');
    await page.locator('.demo-pot-stage').scrollIntoViewIfNeeded();
    const visual = await potGeometry(page);
    writeFileSync(`${process.env.E2E_ARTIFACTS}/full-${viewport.width}-pot-geometry.json`, JSON.stringify(visual));
    check(visual.nav !== null && visual.panel !== null && visual.pot !== null, 'pot layout elements present');
    check(visual.pot.width > 150 && visual.pot.height > 150 && visual.potIntersectsViewport && visual.potCenterVisible && visual.potHitBelongsToStage, 'pot artwork visibly rendered and not covered');
    check(visual.characters.length === 2 && visual.characters.every(character => character.loaded && character.width >= 44 && character.height >= 44)
      && visual.charactersInsideStage, 'characters visible within dedicated pot stage');
    if (viewport.width <= 900) check(visual.nav.bottom <= visual.stage.top + 1 && visual.stage.bottom <= visual.panel.top + 1
      && visual.stage.height >= 390, 'mobile month pot panel stack does not overlap');
    else check((visual.stage.right <= visual.panel.left + 1 || visual.panel.right <= visual.stage.left + 1)
      && visual.nav.bottom <= visual.stage.top + 1, 'desktop pot and controls do not overlap');
    await shot(page, 'pot-leaking');
    const slider = page.getByRole('slider', { name: '카페 한도', exact: true });
    await expect(slider).toHaveValue('40000'); await expect(slider).toBeEnabled();
    const raisedPromise = page.waitForResponse(response => response.request().method() === 'PATCH' && pathOf(response.url()) === '/api/budgets');
    await slider.focus(); for (let step = 0; step < 4; step++) await page.keyboard.press('ArrowRight');
    await expect(slider).toHaveValue('60000');
    check((await raisedPromise).status() === 200, 'real threshold raise saved');
    await expect(slider).toBeEnabled();
    await expect(page.getByText('완벽하오! 새는 돈이 없소!', { exact: true })).toBeVisible();
    await expect(page.locator('.crack')).toHaveCount(0); await expect(page.locator('#waters foreignObject')).toHaveCount(0);
    const raised = await snapshot();
    check(raised.cafeBudgetRaised === seeded.cafeBudgetRaised + 1 && raised.cafeBudgetOriginal === seeded.cafeBudgetOriginal - 1, 'actual SQL threshold raised');
    const resetPromise = page.waitForResponse(response => response.request().method() === 'PATCH' && pathOf(response.url()) === '/api/budgets');
    await slider.focus(); for (let step = 0; step < 4; step++) await page.keyboard.press('ArrowLeft');
    check((await resetPromise).status() === 200, 'real threshold restore saved');
    await expect(slider).toHaveValue('40000'); await expect(slider).toBeEnabled();
    await expect(page.locator('.crack')).toHaveCount(1); await expect(page.locator('#waters foreignObject')).toHaveCount(1);
    const reset = await snapshot();
    check(reset.cafeBudgetRaised === seeded.cafeBudgetRaised && reset.cafeBudgetOriginal === seeded.cafeBudgetOriginal, 'actual SQL threshold restored');

    phase = 'chart'; await gotoDemoChart(page); await chooseChartMonth(page, month);
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 18,000원');
    const practice = page.getByRole('row').filter({ hasText: '합성 장보기(분류 연습)' });
    await practice.scrollIntoViewIfNeeded(); await expect(practice).toContainText('30,000 냥');
    await expect(practice.getByRole('combobox')).toContainText('카페');
    await noOverflow(page, 'chart-detail');
    const patchPromise = page.waitForResponse(response => response.request().method() === 'PATCH' && /\/transactions\/\d+\/category$/.test(pathOf(response.url())));
    const changedAnnual = page.waitForResponse(response => pathOf(response.url()) === '/api/transactions' && response.status() === 200);
    await practice.getByRole('combobox').click(); await page.getByRole('option', { name: '마트 / 편의점', exact: true }).click();
    check((await patchPromise).status() === 200, 'actual category PATCH');
    const afterAnnual: unknown = await (await changedAnnual).json();
    check(Array.isArray(afterAnnual) && afterAnnual.some(value => object(value) && value.date === latest.date && value.totalAmount === 908000 && value.leaked === false), 'annual same total leakfalse');
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원'); await expect(page.locator('.jp-total')).toContainText('908,000원');
    check((await snapshot()).changed === before.changed + 1, 'actual SQL category change');
    await page.getByRole('button', { name: '닫기', exact: true }).click();

    phase = 'advice'; await clickDemoMenu(page, '두꺼비의 조언'); await expect(page).toHaveURL(/\/toadAdvice$/);
    await expect(page.getByText('축하하오! 과소비 항목이 없소!', { exact: true })).toBeVisible();
    await expect(page.getByText(/미리 생성된 분석 결과/)).toBeVisible();
    await noOverflow(page, 'advice');
    await page.locator('.ta-month-button').filter({ hasText: new RegExp(`(?:^|\\s)${historicMonth}월$`) }).click();
    const adviceCard = page.getByRole('button', { name: /문화생활/ }); await adviceCard.click();
    const adviceDialog = page.getByRole('dialog', { name: '문화생활', exact: true });
    await expect(adviceDialog).toContainText('120,000냥'); await expect(adviceDialog).toContainText('180,000냥');
    await expect(adviceDialog).toContainText('60,000냥');
    const commentHeading = adviceDialog.getByText('샘플 분석을 바탕으로 한 두꺼비의 코멘트', { exact: true });
    await commentHeading.scrollIntoViewIfNeeded(); await expect(commentHeading).toBeVisible();
    await expect.poll(() => adviceDialog.evaluate(element => Number(getComputedStyle(element).opacity))).toBe(1);
    const adviceGeometry = await adviceDialog.evaluate(element => {
      const box = element.getBoundingClientRect(), style = getComputedStyle(element);
      const hit = document.elementFromPoint((box.left + box.right) / 2, (box.top + box.bottom) / 2);
      return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height,
        opacity: Number(style.opacity), viewportWidth: innerWidth, viewportHeight: innerHeight,
        scrollHeight: element.scrollHeight, clientHeight: element.clientHeight,
        scrollable: ['auto', 'scroll'].includes(style.overflowY), hitWithinDialog: hit !== null && element.contains(hit) };
    });
    check(adviceGeometry.left >= -1 && adviceGeometry.right <= viewport.width + 1 && adviceGeometry.top >= -1
      && adviceGeometry.bottom <= viewport.height + 1 && adviceGeometry.opacity === 1 && adviceGeometry.hitWithinDialog
      && (adviceGeometry.scrollHeight <= adviceGeometry.clientHeight + 1 || adviceGeometry.scrollable), 'advice dialog visible unclipped and scrollable');
    writeFileSync(`${process.env.E2E_ARTIFACTS}/full-${viewport.width}-advice-geometry.json`, JSON.stringify(adviceGeometry));
    await noOverflow(page, 'advice-modal'); await shot(page, 'advice');
    await page.keyboard.press('Escape'); await expect(adviceDialog).toHaveCount(0); await expect(adviceCard).toBeFocused();

    phase = 'profile'; await clickDemoMenu(page, '콩쥐의 곳간'); await expect(page).toHaveURL(/\/mypage$/);
    const profile = await openProfile(page); await noOverflow(page, 'profile');
    await expect(profile.getByRole('button', { name: '여성', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await profile.getByRole('button', { name: '남성', exact: true }).click(); await profile.getByRole('combobox', { name: '샘플 나이' }).selectOption('30');
    await profile.getByRole('button', { name: /샘플 카드 B/ }).click(); await profile.getByRole('button', { name: '변경 취소', exact: true }).click();
    await expect(profile.getByRole('button', { name: '여성', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(profile.getByRole('combobox', { name: '샘플 나이' })).toHaveValue('20');
    await profile.getByRole('button', { name: '남성', exact: true }).click(); await profile.getByRole('combobox', { name: '샘플 나이' }).selectOption('30');
    await profile.getByRole('button', { name: /샘플 카드 B/ }).click(); await profile.getByRole('button', { name: '샘플 설정 저장', exact: true }).click();
    await expect(profile.getByRole('status')).toHaveText('샘플 설정을 저장했어요.');
    await shot(page, 'profile'); await profile.getByRole('button', { name: '정보 창 닫기', exact: true }).click();
    await page.getByRole('button', { name: '샘플 정보 보기', exact: true }).click();
    await expect(profile.getByRole('button', { name: '남성', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(profile.getByRole('combobox', { name: '샘플 나이' })).toHaveValue('30');
    await profile.getByRole('button', { name: '정보 창 닫기', exact: true }).click();

    phase = 'user-info'; await page.getByRole('link', { name: '정보 입력 과정 체험', exact: true }).click();
    await expect(page).toHaveURL(/\/userInfo$/); await noOverflow(page, 'user-info');
    await page.getByRole('button', { name: '샘플 설정 시작', exact: true }).click();
    await page.getByRole('button', { name: '남성', exact: true }).click(); await page.getByRole('button', { name: '다음', exact: true }).click();
    await page.getByRole('button', { name: '30세', exact: true }).click(); await page.getByRole('button', { name: '다음', exact: true }).click();
    await page.getByRole('button', { name: /샘플 카드 B/ }).click(); await page.getByRole('button', { name: '샘플 설정 완료', exact: true }).click();
    await expect(page).toHaveURL(/\/pot\/\d+$/); await expect(page.getByText('완벽하오! 새는 돈이 없소!', { exact: true })).toBeVisible();

    phase = 'restore-held';
    let release: () => void = () => {}, observed: () => void = () => {};
    const gate = new Promise<void>(resolve => { release = resolve; }), seen = new Promise<void>(resolve => { observed = resolve; });
    await page.route(`${origin}${auth}reissue`, async route => { observed(); await gate; await route.continue(); });
    const restored = page.waitForResponse(response => pathOf(response.url()) === `${auth}reissue` && response.status() === 200);
    const restoredSession = page.waitForResponse(response => pathOf(response.url()) === `${auth}session` && response.status() === 200);
    const reload = page.reload();
    try {
      await seen; await expect(page.getByText('체험 연결을 확인하고 있습니다.', { exact: true })).toBeVisible();
      await expect(page.locator('[data-demo-page="pot"]')).toHaveCount(0);
      check(!events.some(event => event.phase === 'restore-held' && protectedPath(event.path)), 'restore protected requests zero');
      phase = 'restored'; release(); await reload;
    } finally { release(); }
    const reissued = await restored;
    await parsePublicDemoSetCookie(reissued, { deployment: 'public-demo', operation: 'issue' });
    check(await deadline(await restoredSession) === firstDeadline, 'session absolute deadline retained');
    await page.unroute(`${origin}${auth}reissue`);
    await expect(page.getByText('완벽하오! 새는 돈이 없소!', { exact: true })).toBeVisible();
    await expect(slider).toHaveValue('40000');
    await gotoDemoChart(page); await chooseChartMonth(page, month);
    await expect(practice.getByRole('combobox')).toContainText('마트 / 편의점');
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원');
    await page.getByRole('button', { name: '닫기', exact: true }).click();
    await clickDemoMenu(page, '콩쥐의 곳간'); const resetProfile = await openProfile(page);
    await expect(resetProfile.getByRole('button', { name: '여성', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(resetProfile.getByRole('combobox', { name: '샘플 나이' })).toHaveValue('20');
    await expect(resetProfile.getByRole('button', { name: /샘플 카드 A/ })).toHaveAttribute('aria-pressed', 'true');
    await resetProfile.getByRole('button', { name: '정보 창 닫기', exact: true }).click();
    await clickDemoMenu(page, '마당');
    const resume = page.getByRole('button', { name: '체험 이어가기', exact: true }); await expect(resume).toBeEnabled();

    phase = 'logout';
    // Return through the real navigation to a guarded page with an authenticated header.
    await resume.click(); await expect(page).toHaveURL(/\/pot\/\d+$/);
    const logoutPromise = page.waitForResponse(response => pathOf(response.url()) === `${auth}logout`);
    await clickDemoMenu(page, '체험 종료'); const logout = await logoutPromise;
    check(logout.status() === 204, 'actual logout'); await parsePublicDemoSetCookie(logout, { deployment: 'public-demo', operation: 'delete' });
    await expect(start).toBeEnabled(); check(!(await context.cookies()).some(row => row.name === 'demoRefreshToken'), 'cookie removed');
    check((await snapshot()).sessions === 0, 'Redis session revoked');
    await page.goto(`/pot/${month}`); await expect(start).toBeEnabled(); await expect(page.locator('[data-demo-page="pot"]')).toHaveCount(0);
    check(events.filter(event => event.path === `${auth}login` && event.status === undefined).length === 1, 'single login and no auto-new-visit');
    check(events.filter(event => event.path === '/api/budgets' && event.method === 'PATCH' && event.status === undefined).length === 2, 'only two explicit threshold writes');
    check(events.filter(event => /\/transactions\/:n\/category$/.test(event.path) && event.method === 'PATCH' && event.status === undefined).length === 1, 'one explicit category write');
    writeFileSync(`${process.env.E2E_ARTIFACTS}/full-${viewport.width}-contract.json`, JSON.stringify({ viewport, pages: ['landing', 'pot', 'chart', 'advice', 'mypage', 'user-info'],
      seedDelta: { users: 1, cards: 1, transactions: 240, budgets: 72 }, threshold: { before: 40000, raised: 60000, restored: 40000 },
      total: 908000, leakBefore: 18000, leakAfter: 0, annualLeaked: false, historicalAdviceLeak: 120000,
      profileMemoryOnly: true, profileReloadReset: true, sessionDeadlineUnchanged: true, restoredCategory: true,
      loginCount: 1, automaticLogin: 0, externalAttempts: external, forbiddenAttempts: forbidden, cookieRemoved: true, protectedPageBlocked: true, tokenRevocationCoveredByCore: true }, null, 2));
  });
}
