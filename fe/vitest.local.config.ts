import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  envDir: false,
  define: {
    'import.meta.env.VITE_AUTH_MODE': JSON.stringify('demo'),
    'import.meta.env.VITE_DEMO_DATA_MODE': JSON.stringify('local'),
    'import.meta.env.VITE_BACK_URL': 'undefined',
  },
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://127.0.0.1/' } },
    env: { VITE_AUTH_MODE: 'demo', VITE_DEMO_DATA_MODE: 'local' },
    setupFiles: ['./tests/setup.ts'],
    include: ['./tests/local/**/*.test.{ts,tsx}'],
    fileParallelism: false,
    restoreMocks: true,
    testTimeout: 10000,
  },
});
