import { defineConfig } from '@playwright/test';
import base from './playwright.config';

export default defineConfig(base, { testMatch: 'full-experience.spec.ts', workers: 1, retries: 0, maxFailures: 1, timeout: 180_000 });
