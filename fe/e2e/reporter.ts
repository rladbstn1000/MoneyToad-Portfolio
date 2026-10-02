import type { Reporter, TestCase, TestResult, FullResult } from '@playwright/test/reporter';
import { writeFileSync } from 'node:fs';

// Never serialize Playwright error objects, call arguments, headers or attachments.
export default class SafeReporter implements Reporter {
  private cases: { title: string; status: string; milliseconds: number; location?: string }[] = [];
  onTestEnd(test: TestCase, result: TestResult) {
    const location = result.errors[0]?.stack?.match(/(?:demo|mobile-chart|cold-start|full-experience)\.spec\.ts:(\d+):\d+/)?.[1];
    this.cases.push({ title: test.title, status: result.status, milliseconds: result.duration, location });
    console.log(`${test.title}: ${result.status}${location ? ` (assertion line ${location})` : ''}`);
  }
  onEnd(result: FullResult) {
    writeFileSync(`${process.env.E2E_ARTIFACTS}/tests.json`, JSON.stringify({ status: result.status, cases: this.cases }, null, 2));
  }
}
