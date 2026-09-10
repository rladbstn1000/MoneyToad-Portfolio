import { build, preview } from 'vite';
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import base from '../vite.config.ts';

const work = process.env.E2E_WORK!;
const origin = process.env.E2E_ORIGIN!;
const port = Number(new URL(origin).port);
const probePort = Number(process.env.E2E_PROBE_PORT);
const https = origin.startsWith('https:') ? {
  key: readFileSync(`${work}/key.pem`), cert: readFileSync(`${work}/cert.pem`),
} : undefined;
const proxy = { '/api': { target: process.env.E2E_BACKEND!, changeOrigin: false } };
// Test-build-only asset override. No product CSS edits, no downloaded font, no API mocks.
const config = {
  ...base, configFile: false as const, envDir: false as const,
  plugins: [...base.plugins!, {
    name: 'e2e-system-font', enforce: 'pre' as const,
    transform(code: string, id: string) {
      if (!id.endsWith('.css')) return;
      return code.replace(/@font-face\s*\{[^}]*https:\/\/gcore\.jsdelivr\.net[^}]*\}/g, '');
    },
    configurePreviewServer(server: import('vite').PreviewServer) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.startsWith('/api/auth/demo/')) {
          const path = req.url.split('?')[0];
          appendFileSync(`${work}/gateway.jsonl`, JSON.stringify({ method: req.method, path }) + '\n');
          res.on('finish', () => appendFileSync(`${work}/gateway.jsonl`, JSON.stringify({ method: req.method, path, status: res.statusCode }) + '\n'));
        }
        if (req.url === '/__e2e_probe.html' || req.url === '/api/auth/demo/__e2e_probe.html') {
          res.setHeader('Content-Type', 'text/html');
          res.end('<!doctype html><title>E2E inert probe</title>');
        } else if (req.url === '/__e2e_ready') res.end(process.env.E2E_RUN_ID);
        else next();
      });
    },
  }],
  build: { outDir: `${work}/dist`, emptyOutDir: true },
};
await build(config);
const servers: import('vite').PreviewServer[] = [];
for (const selectedPort of [port, probePort]) {
  servers.push(await preview({ ...config, preview: {
    host: '127.0.0.1', port: selectedPort, strictPort: true, https, proxy, cors: false,
  } }));
}
writeFileSync(`${work}/preview-ready`, 'ready');
const close = () => { for (const server of servers) server.httpServer.close(); process.exit(0); };
process.on('SIGTERM', close);
process.on('SIGINT', close);
