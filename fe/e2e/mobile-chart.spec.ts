import { clickDemoMenu, gotoDemoChart } from './demoNavigation';
import { test } from './fixtures';
import { expect, type Page } from '@playwright/test';
import { writeFileSync } from 'node:fs';

const baseline = process.env.E2E_MOBILE_PHASE === 'before';
const origin = process.env.E2E_ORIGIN!;
const auth = '/api/auth/demo/';
const merchant = '합성 장보기(분류 연습)';
type Event = { order: number; method: string; path: string; status?: number; phase: string };
let events: Event[] = [], external = 0, phase = 'initial';
const pathname = (url: string) => new URL(url).pathname;
const protectedPath = (path: string) => /^\/api\/(users|transactions|budgets|cards)(\/|$)/.test(path);
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;
function check(value: boolean, label: string): asserts value {
  if (!value) {
    writeFileSync(`${process.env.E2E_ARTIFACTS}/failed-check.json`, JSON.stringify({ check: label, phase }));
    throw new Error(label);
  }
}
async function snapshot(): Promise<Record<string, number>> {
  const response = await fetch(`${process.env.E2E_CONTROL}/snapshot`);
  check(response.ok, 'owned snapshot available');
  return response.json() as Promise<Record<string, number>>;
}
async function chooseMonth(page: Page, month: number) {
  const mobileMonths = page.getByRole('navigation', { name: '월별 상세 보기', exact: true });
  if (page.viewportSize()!.width <= 768) {
    await expect(mobileMonths).toBeVisible();
    await mobileMonths.getByRole('button', { name: new RegExp(`년 ${month}월 상세 보기$`) }).click();
    await expect(page.locator('.jp-total')).toContainText('908,000원');
    return;
  }
  const dot = page.locator('#screen1 .recharts-line-dots image').nth(month - 1);
  await expect(page.locator('#screen1 .recharts-line-dots image')).toHaveCount(12);
  await page.locator('.jp-linechart-wrap').scrollIntoViewIfNeeded();
  await expect(dot).toBeVisible();
  // Recharts replaces the hit target with activeDot on pointer entry. Move the
  // pointer over its measured box, then use an ordinary actionable locator click.
  const active = page.locator('#screen1 .recharts-active-dot image');
  await page.mouse.move(0, 0);
  await expect.poll(async () => {
    const box = await dot.boundingBox();
    if (!box) return false;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    const activeBox = await active.boundingBox();
    return activeBox !== null && Math.abs(activeBox.x - box.x) < 1 && Math.abs(activeBox.y - box.y) < 1;
  }, { message: 'active month matches requested visible dot', timeout: 8000 }).toBe(true);
  await active.click({ timeout: 8000 });
  await expect(page.locator('.jp-total')).toContainText('908,000원');
}
async function layout(page: Page) {
  return page.locator('#screen2').evaluate(detail => {
    const rect = (element: Element | null) => {
      if (!element) return null;
      const box = element.getBoundingClientRect();
      return { left: Math.round(box.left), right: Math.round(box.right), width: Math.round(box.width), height: Math.round(box.height), top: Math.round(box.top), bottom: Math.round(box.bottom) };
    };
    const row = [...detail.querySelectorAll('tbody tr')].find(element => element.textContent?.includes('합성 장보기(분류 연습)'))!;
    const cells = [...row.querySelectorAll('td')];
    const control = row.querySelector('[role="combobox"]')!;
    const panel = row.closest('.jp-panel')!;
    const values = [cells[1], cells[2], control].map(element => {
      const box = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      const textRects: DOMRect[] = [];
      const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (!node.textContent?.trim()) continue;
        const range = document.createRange(); range.selectNodeContents(node);
        textRects.push(...[...range.getClientRects()].filter(item => item.width > 0 && item.height > 0));
      }
      const contentBoxes = [box, ...textRects];
      let clippedByAncestor = false;
      for (let parent = element.parentElement; parent && parent !== detail.parentElement; parent = parent.parentElement) {
        const parentStyle = getComputedStyle(parent);
        const parentBox = parent.getBoundingClientRect();
        if (['auto', 'scroll', 'hidden', 'clip'].includes(parentStyle.overflowX)
          && contentBoxes.some(item => item.left < parentBox.left - 1 || item.right > parentBox.right + 1)) clippedByAncestor = true;
        if (['auto', 'scroll', 'hidden', 'clip'].includes(parentStyle.overflowY)
          && contentBoxes.some(item => item.top < parentBox.top - 1 || item.bottom > parentBox.bottom + 1)) clippedByAncestor = true;
      }
      return { ...rect(element), clientWidth: element.clientWidth, scrollWidth: element.scrollWidth, clippedByAncestor,
        fontSize: parseFloat(style.fontSize), visibleHorizontally: contentBoxes.every(item => item.left >= -1 && item.right <= innerWidth + 1),
        visibleVertically: contentBoxes.every(item => item.top >= -1 && item.bottom <= innerHeight + 1),
        visibleStyle: style.visibility === 'visible' && style.display !== 'none' && parseFloat(style.opacity) > 0,
        textClipped: element.scrollWidth > element.clientWidth + 1 && ['hidden', 'clip'].includes(style.overflowX) };
    });
    return { viewportWidth: innerWidth, bodyOverflow: Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) - innerWidth,
      detail: rect(detail), card: rect(detail.querySelector('.jp-card')), panel: rect(panel), table: rect(row.closest('table')),
      piePanel: rect(detail.querySelector('.jp-pie-panel')),
      gridColumns: getComputedStyle(detail.querySelector('.jp-grid')!).gridTemplateColumns,
      merchant: values[0], amount: values[1], select: values[2], summary: rect(detail.querySelector('.jp-head-actions')),
      readable: values.every(item => item.visibleHorizontally && item.visibleVertically && item.visibleStyle && !item.textClipped && !item.clippedByAncestor && item.width! >= 44 && item.fontSize >= 13),
      touchTarget: control.getBoundingClientRect().height >= 44,
      panelOverflow: panel.scrollWidth - panel.clientWidth };
  });
}
async function shot(page: Page, filename: string) {
  // The opaque full-viewport detail overlay contains only synthetic merchant/amount/category data.
  // Capture this element so the authenticated header behind it is not part of the screenshot.
  await page.locator('#screen2').screenshot({ path: `${process.env.E2E_ARTIFACTS}/${filename}.png` });
}

test.beforeEach(async ({ context }) => {
  events = []; external = 0; phase = 'initial';
  await context.route('**/*', async route => {
    if (new URL(route.request().url()).origin !== origin) { external++; await route.abort(); return; }
    await route.continue();
  });
  await context.routeWebSocket(/.*/, socket => { external++; socket.close(); });
  context.on('request', request => {
    if (pathname(request.url()).startsWith('/api/')) events.push({ order: events.length, method: request.method(),
      path: pathname(request.url()).replace(/\/\d+(?=\/|$)/g, '/:n'), phase });
  });
  context.on('response', response => {
    if (pathname(response.url()).startsWith('/api/')) events.push({ order: events.length, method: response.request().method(),
      path: pathname(response.url()).replace(/\/\d+(?=\/|$)/g, '/:n'), status: response.status(), phase });
  });
});

test.afterEach(async ({ page }, info) => {
  const state = await snapshot();
  const viewport = page.viewportSize();
  const suffix = viewport ? `${viewport.width}x${viewport.height}` : 'viewport';
  writeFileSync(`${process.env.E2E_ARTIFACTS}/${suffix}-network.json`, JSON.stringify({ events, externalAttempts: external, backendAttempts: state.outbound }));
  try {
    check(external === 0 && state.outbound === 0, 'external application requests zero');
    check(!events.some(event => event.path === '/api/transactions/peer'), 'peer API never requested');
  } finally {
    for (const error of info.errors) {
      const line = error.stack?.match(/mobile-chart\.spec\.ts:(\d+):\d+/)?.[1];
      error.message = ''; error.errorContext = undefined;
      error.stack = line ? `at mobile-chart.spec.ts:${line}:1` : undefined;
    }
  }
});

for (const viewport of [{ width: 390, height: 844 }, { width: 768, height: 1024 }, { width: 1440, height: 1000 }]) {
  test(`Chart real responsive flow ${viewport.width}x${viewport.height}`, async ({ page, context }) => {
    await page.setViewportSize(viewport);
    const initial = page.waitForResponse(response => pathname(response.url()) === `${auth}reissue`);
    await page.goto('/');
    check((await initial).status() === 401, 'anonymous initial restore');
    const start = page.getByRole('button', { name: '샘플 데이터로 체험하기', exact: true });
    await expect(start).toBeEnabled();
    check(!events.some(event => event.path === `${auth}login`), 'automatic login zero');
    const before = await snapshot();
    const login = page.waitForResponse(response => pathname(response.url()) === `${auth}login`);
    const annual = page.waitForResponse(response => pathname(response.url()) === '/api/transactions' && response.status() === 200);
    phase = 'login'; await start.click();
    check((await login).status() === 201, 'login created');
    await gotoDemoChart(page);
    const seeded = await snapshot();
    check(seeded.users === before.users + 1 && seeded.cards === before.cards + 1
      && seeded.transactions === before.transactions + 240 && seeded.budgets === before.budgets + 72
      && seeded.financial === 0 && seeded.jobs === 0, 'real visitor seed counts');
    const annualValue: unknown = await (await annual).json();
    check(Array.isArray(annualValue) && annualValue.length === 12, 'annual twelve months');
    const latest: unknown = annualValue.at(-1);
    check(object(latest) && typeof latest.date === 'string' && latest.totalAmount === 908000 && latest.leaked === true, 'current anchor exact');
    const month = Number(latest.date.split('-')[1]);
    let overview: Record<string, number> | undefined;
    if (!baseline) {
      overview = await page.locator('#screen1').evaluate(element => ({
        documentOverflow: Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) - innerWidth,
        localOverflow: element.scrollWidth - element.clientWidth,
      }));
      check(overview.documentOverflow <= 1 && overview.localOverflow <= 1, 'overview no horizontal overflow');
      if (viewport.width <= 768) {
        const monthButtons = page.getByRole('navigation', { name: '월별 상세 보기', exact: true }).getByRole('button');
        await expect(monthButtons).toHaveCount(12);
        check(await monthButtons.evaluateAll(elements => elements.every(element => {
          const box = element.getBoundingClientRect();
          return box.width >= 44 && box.height >= 44;
        })), 'mobile month controls touch size');
      }
    }
    if (!baseline && viewport.width === 390) await page.locator('#screen1').screenshot({
      path: `${process.env.E2E_ARTIFACTS}/inspect-overview.png`, mask: [page.locator('header')] });
    let selectedAtViewport = true;
    phase = 'month-at-viewport';
    try { await chooseMonth(page, month); }
    catch (error) {
      if (!baseline) throw error;
      selectedAtViewport = false;
      // Separate diagnostic only: do not claim the failed mobile month selection passed.
      phase = 'month-desktop-diagnostic';
      await page.setViewportSize({ width: 1440, height: 1000 });
      await expect.poll(() => page.locator('.jp-linechart-wrap').evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(800);
      await chooseMonth(page, month);
      await page.setViewportSize(viewport);
    }
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 18,000원');
    const row = page.getByRole('row').filter({ hasText: merchant });
    await expect(row).toHaveCount(1);
    if (!baseline) await row.scrollIntoViewIfNeeded();
    const originalLayout = await layout(page);
    const evidence: Record<string, unknown> = { phase: baseline ? 'before' : 'after', viewport, selectedAtViewport, originalLayout, overview };
    if (baseline) {
      if (viewport.width === 390) await shot(page, 'mobile-390-before');
      writeFileSync(`${process.env.E2E_ARTIFACTS}/layout-${viewport.width}.json`, JSON.stringify(evidence, null, 2));
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.getByRole('button', { name: '닫기', exact: true }).click();
      const logout = page.waitForResponse(response => pathname(response.url()) === `${auth}logout`);
      await clickDemoMenu(page, '체험 종료');
      check((await logout).status() === 204, 'baseline visitor logout');
      return;
    }
    check(originalLayout.bodyOverflow <= 1, 'no document horizontal overflow');
    for (const summary of [page.locator('.jp-total'), page.locator('.jp-leak')]) {
      await summary.scrollIntoViewIfNeeded();
      check(await summary.evaluate(element => {
        const box = element.getBoundingClientRect();
        return box.left >= -1 && box.right <= innerWidth + 1 && box.top >= -1 && box.bottom <= innerHeight + 1
          && element.scrollWidth <= element.clientWidth + 1
          && parseFloat(getComputedStyle(element).fontSize) >= 16;
      }), 'summary text readable without clipping');
    }
    await row.scrollIntoViewIfNeeded();
    evidence.summaryReadable = true;
    check(originalLayout.readable, 'merchant amount and category readable');
    const columns = originalLayout.gridColumns.trim().split(/\s+/);
    if (viewport.width === 1440) {
      check(columns.length === 2 && originalLayout.panel !== null && originalLayout.piePanel !== null
        && originalLayout.panel.right <= originalLayout.piePanel.left
        && Math.abs(originalLayout.panel.top - originalLayout.piePanel.top) <= 1, 'desktop two-column contract preserved');
    } else {
      check(columns.length === 1, 'mobile detail single-column contract');
    }
    if (viewport.width <= 768) check(originalLayout.touchTarget, 'mobile select touch target at least44');
    await expect(row).toContainText('30,000 냥');
    await expect(row.getByRole('combobox')).toContainText('카페');
    phase = 'patch';
    const changed = page.waitForResponse(response => response.request().method() === 'PATCH' && /\/transactions\/\d+\/category$/.test(pathname(response.url())));
    const refreshed = page.waitForResponse(response => pathname(response.url()) === '/api/transactions' && response.status() === 200);
    await row.getByRole('combobox').click();
    const option = page.getByRole('option', { name: '마트 / 편의점', exact: true });
    await expect(option).toBeVisible();
    const optionBox = await option.boundingBox();
    check(optionBox !== null && optionBox.x >= -1 && optionBox.x + optionBox.width <= viewport.width + 1, 'option within viewport');
    await option.click();
    check((await changed).status() === 200, 'actual PATCH');
    const afterValue: unknown = await (await refreshed).json();
    check(Array.isArray(afterValue) && afterValue.some(item => object(item) && item.date === latest.date && item.totalAmount === 908000 && item.leaked === false), 'annual same total and leakfalse');
    await expect(page.locator('.jp-total')).toContainText('908,000원');
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원');
    await expect(row.getByRole('combobox')).toContainText('마트 / 편의점');
    const patched = await snapshot();
    check(patched.changed === before.changed + 1, 'real SQL category changed once');
    const patchOrder = events.find(event => event.phase === 'patch' && event.method === 'PATCH' && event.status === 200)!.order;
    await expect.poll(() => events.some(event => event.order > patchOrder && event.method === 'GET' && event.status === 200 && /\/transactions\/:n\/:n$/.test(event.path)) && events.some(event => event.order > patchOrder && event.method === 'GET' && event.status === 200 && event.path.endsWith('/categories'))).toBe(true);
    const modifiedLayout = await layout(page);
    check(modifiedLayout.bodyOverflow <= 1 && modifiedLayout.readable, 'layout preserved afterPATCH');
    if (viewport.width <= 768) await shot(page, `mobile-${viewport.width}-after`);
    if (viewport.width === 390) {
      await page.locator('.jp-pie-panel').scrollIntoViewIfNeeded();
      await expect.poll(() => page.locator('.jp-pie-panel path.recharts-sector').evaluateAll(paths => paths.filter(element => {
        const box = element.getBoundingClientRect();
        return box.width > 1 && box.height > 1;
      }).length), { message: 'actual pie sectors rendered after aggregation' }).toBe(6);
      // Recharts retains one empty fallback <text> for the <3% slice. Count
      // visible text content, not all text nodes, after its animation completes.
      await expect.poll(() => page.locator('.jp-pie-panel .recharts-pie-labels text')
        .evaluateAll(elements => elements.filter(element => element.textContent?.trim()).length)).toBe(5);
      const legend = page.getByRole('list', { name: '카테고리별 소비', exact: true });
      await expect(legend.getByRole('listitem')).toHaveCount(6);
      const expectedCategories = [
        ['식비', '180,000'], ['카페', '28,000'], ['마트 / 편의점', '120,000'],
        ['문화생활', '20,000'], ['교통 / 차량', '60,000'], ['주거 / 통신', '500,000'],
      ];
      for (const [category, amount] of expectedCategories) {
        const item = legend.getByRole('listitem').filter({ hasText: category });
        await expect(item).toHaveCount(1);
        await expect(item.locator('.jp-pie-legend-name')).toHaveText(category);
        await expect(item.locator('.jp-pie-legend-amount')).toContainText(amount);
      }
      await page.locator('.jp-pie-panel').scrollIntoViewIfNeeded();
      const pieObservation = await page.locator('.jp-pie-panel').evaluate(panel => {
        const text = [...panel.querySelectorAll('.recharts-pie-labels text')].filter(element => element.textContent?.trim());
        const boxes = text.map(element => element.getBoundingClientRect());
        let overlaps = 0;
        for (let first = 0; first < boxes.length; first++) for (let second = first + 1; second < boxes.length; second++) {
          const a = boxes[first], b = boxes[second];
          if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1) overlaps++;
        }
        const legendBoxes = [...panel.querySelectorAll('.jp-mobile-pie-legend li')].map(element => element.getBoundingClientRect());
        return { labelGroups: panel.querySelectorAll('.recharts-pie-labels').length,
          textElements: panel.querySelectorAll('.recharts-pie-labels text').length, visibleTextElements: text.length,
          visibleLabelOverlaps: overlaps, legendEntries: legendBoxes.length,
          legendVisible: legendBoxes.every(box => box.left >= -1 && box.right <= innerWidth + 1 && box.top >= -1 && box.bottom <= innerHeight + 1),
          svgGeometry: [...panel.querySelectorAll('svg')].map(element => { const box = element.getBoundingClientRect(); return { width: box.width, height: box.height }; }),
          sectorGeometry: [...panel.querySelectorAll('path.recharts-sector')].map(element => { const box = element.getBoundingClientRect(); return { width: box.width, height: box.height }; }),
        };
      });
      writeFileSync(`${process.env.E2E_ARTIFACTS}/pie-observation.json`, JSON.stringify(pieObservation));
      await shot(page, 'inspect-pie');
      check(pieObservation.visibleLabelOverlaps === 0 && pieObservation.legendVisible, 'mobile pie percentages and legend readable');
      evidence.pieVisibleSectors = 6;
      evidence.pieVisibleLabels = 5;
      evidence.pieLegendEntries = 6;
    }
    phase = 'restore-held';
    let release = () => {};
    let seen = () => {};
    const held = new Promise<void>(resolve => { release = resolve; });
    const requested = new Promise<void>(resolve => { seen = resolve; });
    await page.route(`${origin}${auth}reissue`, async route => { seen(); await held; await route.continue(); });
    const restored = page.waitForResponse(response => pathname(response.url()) === `${auth}session` && response.status() === 200);
    const reload = page.reload();
    try {
      await requested;
      await expect(page.getByText('체험 연결을 확인하고 있습니다.', { exact: true })).toBeVisible();
      await expect(page.locator('#screen1')).toHaveCount(0);
      check(!events.some(event => event.phase === 'restore-held' && protectedPath(event.path)), 'restoring protected HTTPzero');
    } finally { phase = 'restored'; release(); }
    await reload; await restored;
    await page.unroute(`${origin}${auth}reissue`);
    await expect(page.locator('#screen1')).toBeVisible();
    await chooseMonth(page, month);
    await expect(row.getByRole('combobox')).toContainText('마트 / 편의점');
    await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원');
    await expect(page.locator('.jp-total')).toContainText('908,000원');
    check(events.filter(event => event.path === `${auth}login` && event.status === undefined).length === 1, 'reload loginzero');
    evidence.modifiedLayout = modifiedLayout;
    evidence.contract = { totalBefore: 908000, totalAfter: 908000, leakBefore: 18000, leakAfter: 0, annualLeakedAfter: false,
      patchSucceeded: true, sqlChangeObserved: true, restorePreserved: true, automaticLogin: 0 };
    await page.getByRole('button', { name: '닫기', exact: true }).click();
    const loggedOut = page.waitForResponse(response => pathname(response.url()) === `${auth}logout`);
    phase = 'logout'; await clickDemoMenu(page, '체험 종료');
    check((await loggedOut).status() === 204, 'logout success');
    check(!(await context.cookies()).some(item => item.name === 'demoRefreshToken'), 'cookie removed');
    check((await snapshot()).sessions === before.sessions, 'session removed');
    await page.goto('/chart'); await expect(start).toBeEnabled();
    check(events.filter(event => event.path === `${auth}login` && event.status === undefined).length === 1, 'protected reentry no newlogin');
    writeFileSync(`${process.env.E2E_ARTIFACTS}/layout-${viewport.width}.json`, JSON.stringify(evidence, null, 2));
  });
}
