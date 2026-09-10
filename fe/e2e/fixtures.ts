import { test as base } from '@playwright/test';
import { appendFileSync } from 'node:fs';

// Public BrowserServer API exposes the owned Chromium PID for interrupted-run cleanup.
export const test = base.extend({
  browser: [async ({ playwright }, use) => {
    const server = await playwright.chromium.launchServer({ headless: true, host: '127.0.0.1' });
    const child = server.process();
    appendFileSync(`${process.env.E2E_WORK}/browser-owned.jsonl`, JSON.stringify({ pid: child.pid }) + '\n');
    const browser = await playwright.chromium.connect(server.wsEndpoint());
    appendFileSync(`${process.env.E2E_ARTIFACTS}/browser-version.txt`, browser.version() + '\n');
    try { await use(browser); }
    finally { await browser.close(); await server.close(); }
  }, { scope: 'worker' }],
});
