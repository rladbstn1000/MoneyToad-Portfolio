import { defineConfig } from '@playwright/test';
import base from './playwright.config';

export default defineConfig(base, { testMatch: 'cold-start.spec.ts', timeout: 90_000,
  use: { ...base.use, viewport: { width: 390, height: 844 } } });
