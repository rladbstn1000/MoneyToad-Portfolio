import { defineConfig } from '@playwright/test';
import base from './playwright.config';

export default defineConfig(base, { testMatch: 'mobile-chart.spec.ts', maxFailures: 0, timeout: 90_000 });
