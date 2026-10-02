import { expect, type Page } from '@playwright/test';

/** Use the actual responsive menu; never bypass visibility/actionability. */
export async function clickDemoMenu(page: Page, label: string) {
  const nav = page.getByRole('navigation', { name: '체험 메뉴', exact: true });
  const target = label === '체험 종료' ? nav.getByRole('button', { name: label, exact: true })
    : nav.getByRole('link', { name: label, exact: true });
  // A route may be committed before its header mounts. Do not infer a mobile
  // viewport from a temporarily absent desktop link.
  if (page.viewportSize()!.width <= 900 && !await target.isVisible()) {
    const toggle = nav.getByRole('button', { name: '체험 메뉴', exact: true });
    await expect(toggle).toBeVisible();
    await toggle.click();
  }
  await expect(target).toBeVisible();
  await target.click();
}

export async function gotoDemoChart(page: Page) {
  await expect(page).toHaveURL(/\/pot\/\d+$/);
  await clickDemoMenu(page, '콩쥐의 씀씀이');
  await expect(page).toHaveURL(/\/chart$/);
}
