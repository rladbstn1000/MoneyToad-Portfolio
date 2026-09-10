import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const testOrigin = 'http://127.0.0.1:18080';

export default defineConfig({
  plugins: [react()],
  envDir: false,
  define: { 'import.meta.env.VITE_AUTH_MODE': JSON.stringify('oauth'), 'import.meta.env.VITE_BACK_URL': JSON.stringify(testOrigin) },
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: `${testOrigin}/` } },
    env: { VITE_AUTH_MODE: 'oauth', VITE_BACK_URL: testOrigin },
    setupFiles: ['./tests/setup.ts'],
    include: ['./tests/**/*.test.{ts,tsx}'],
    exclude: ['./tests/demo/**/*.test.{ts,tsx}'],
    fileParallelism: false,
    restoreMocks: true,
    testTimeout: 10000,
  },
});
