import { build, preview } from 'vite';
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import base from '../vite.config.ts';

const work = process.env.E2E_WORK!;
const origin = process.env.E2E_ORIGIN!;
const port = Number(new URL(origin).port);
const probePort = Number(process.env.E2E_PROBE_PORT);
const https = origin.startsWith('https:') ? {
  key: readFileSync(`${work}/key.pem`), cert: readFileSync(`${work}/cert.pem`),
} : undefined;
const proxy = { '/api': { target: process.env.E2E_BACKEND!, changeOrigin: false,
  configure(proxy: import('vite').HttpProxy.ProxyServer) {
    proxy.on('proxyReq', outgoing => {
      outgoing.removeHeader('X-MoneyToad-Gateway');
      outgoing.removeHeader('X-MoneyToad-Client-IP');
      for (const header of outgoing.getHeaderNames()) {
        if (header.toLowerCase().startsWith('x-forwarded-')) outgoing.removeHeader(header);
      }
      if (https) {
        if (!process.env.E2E_GATEWAY_VALUE) throw new Error('E2E gateway configuration missing');
        outgoing.setHeader('X-MoneyToad-Gateway', process.env.E2E_GATEWAY_VALUE);
        outgoing.setHeader('X-MoneyToad-Client-IP', '192.0.2.123'); // Synthetic documentation address.
      }
    });
  },
} };
// Build actual product CSS. No font transform or product API response mock.
const config = {
  ...base, configFile: false as const, envDir: false as const,
  plugins: [...base.plugins!, {
    name: 'e2e-observation',
    configurePreviewServer(server: import('vite').PreviewServer) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.startsWith('/api/auth/demo/')) {
          const path = req.url.split('?')[0];
          appendFileSync(`${work}/gateway.jsonl`, JSON.stringify({ method: req.method, path }) + '\n');
          res.on('finish', () => appendFileSync(`${work}/gateway.jsonl`, JSON.stringify({ method: req.method, path, status: res.statusCode }) + '\n'));
        }
        // Explicit server-side cold-start fixture. Only this initial read-only GET
        // is synthetic; once released every API request uses the real backend.
        if (process.env.E2E_COLD_START_FIXTURE === '1' && req.method === 'GET'
          && req.url === '/api/auth/demo/ready' && !existsSync(`${work}/cold-ready`)) {
          res.statusCode = 503;
          res.setHeader('Content-Type', 'application/json');
          res.setHeader('Cache-Control', 'no-store');
          res.end(JSON.stringify({ ready: false }));
          return;
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
