import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { cp, mkdir, readdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/** Standalone static artifact. No environment files or Pages Functions are loaded. */
export default defineConfig(({ command }) => ({
  plugins: [react(), {
    name: 'local-demo-static-assets',
    async closeBundle() {
      if (command !== 'build') return;
      const output = resolve('dist-local');
      await mkdir(output, { recursive: true });
      for (const entry of await readdir('public', { withFileTypes: true })) {
        if (['_routes.json', '_worker.js', 'functions', '_redirects'].includes(entry.name)) continue;
        await cp(resolve('public', entry.name), resolve(output, entry.name), { recursive: true });
      }
      await writeFile(resolve(output, '_redirects'), '/* /index.html 200\n');
    },
  }],
  envDir: false,
  envPrefix: [],
  define: {
    'import.meta.env.VITE_AUTH_MODE': JSON.stringify('demo'),
    'import.meta.env.VITE_DEMO_DATA_MODE': JSON.stringify('local'),
    'import.meta.env.VITE_BACK_URL': 'undefined',
  },
  publicDir: command === 'serve' ? 'public' : false,
  build: { outDir: 'dist-local', emptyOutDir: true },
}));
