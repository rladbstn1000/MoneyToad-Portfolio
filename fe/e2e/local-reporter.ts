import type { Reporter, TestCase, TestResult, FullResult } from '@playwright/test/reporter';
import { writeFileSync } from 'node:fs';
export default class LocalReporter implements Reporter {
  private cases: { title: string; status: string; milliseconds: number; assertionLine?: string }[] = [];
  private globalErrors = 0;
  onError() { this.globalErrors++; }
  onTestEnd(test: TestCase, result: TestResult) {
    const assertionLine = result.errors[0]?.stack?.match(/local-demo\.spec\.ts:(\d+):\d+/)?.[1];
    this.cases.push({ title: test.title, status: result.status, milliseconds: result.duration, assertionLine });
    console.log(`${test.title}: ${result.status}${assertionLine ? ` (line ${assertionLine})` : ''}`);
  }
  onEnd(result: FullResult) {
    writeFileSync(`${process.env.E2E_ARTIFACTS}/tests.json`, JSON.stringify({ status: result.status, cases: this.cases, globalErrors: this.globalErrors }));
  }
}
