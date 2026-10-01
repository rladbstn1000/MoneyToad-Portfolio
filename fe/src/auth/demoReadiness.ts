import axios from 'axios';
import { demoRateLimitDelay } from './demoRateLimit';
import { demoHttp } from '../api/services/demoAuth';

// A finite automatic readiness budget, not a cold-start completion guarantee.
// Budget exhaustion requires an explicit manual check; never lengthen it automatically.
export const DEMO_READY_DEADLINE_MS = 240_000;
export const DEMO_READY_REQUEST_MS = 10_000;
export const DEMO_READY_INTERVAL_MS = 3_000;
const MAX_ATTEMPTS = 80;

export class DemoReadinessError extends Error {
  constructor() {
    super('데모 서버 준비를 확인하지 못했습니다. 잠시 후 연결을 다시 시도해 주세요.');
    this.name = 'DemoReadinessError';
  }
}

export class DemoStartupSlowError extends DemoReadinessError {
  readonly readinessRetryAt: number | null;
  constructor(readinessRetryAt: number | null = null) {
    super(); this.name = 'DemoStartupSlowError'; this.readinessRetryAt = readinessRetryAt;
  }
}

export type ManualReadinessResult = { ready: boolean; retryAt: number | null };
let flight: { promise: Promise<void>; controller: AbortController } | undefined;
let manualFlight: { promise: Promise<ManualReadinessResult>; controller: AbortController } | undefined;

function pause(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new DemoReadinessError()); return; }
    const abort = () => { clearTimeout(timer); reject(new DemoReadinessError()); };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', abort);
      resolve();
    }, milliseconds);
    signal.addEventListener('abort', abort, { once: true });
  });
}

async function poll(signal: AbortSignal): Promise<void> {
  const deadline = performance.now() + DEMO_READY_DEADLINE_MS;
  let readinessRetryAt: number | null = null;
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const remaining = deadline - performance.now();
    if (signal.aborted) throw new DemoReadinessError();
    if (remaining <= 0) throw new DemoStartupSlowError(readinessRetryAt);
    let ready = false;
    let retryDelay = DEMO_READY_INTERVAL_MS;
    // XHR coerces timeout to an integer; a fractional value below 1 becomes 0
    // (unlimited), so preserve a positive timeout even at the final boundary.
    try { ready = await demoHttp.ready(signal, Math.min(DEMO_READY_REQUEST_MS, Math.ceil(remaining))); }
    catch (error) {
      if (signal.aborted) throw new DemoReadinessError();
      const status = axios.isAxiosError(error) ? error.response?.status : undefined;
      const limitedDelay = demoRateLimitDelay(error, 'DEMO_READINESS_RATE_LIMITED');
      if (limitedDelay !== null) {
        retryDelay = Math.max(DEMO_READY_INTERVAL_MS, limitedDelay);
        readinessRetryAt = Date.now() + limitedDelay;
      }
      else if (status !== undefined && ![408, 500, 502, 503, 504].includes(status)) throw error;
    }
    if (signal.aborted) throw new DemoReadinessError();
    if (performance.now() >= deadline) throw new DemoStartupSlowError(readinessRetryAt);
    if (ready) return;
    if (attempt + 1 < MAX_ATTEMPTS) {
      await pause(Math.min(retryDelay, deadline - performance.now()), signal);
    }
  }
  throw new DemoStartupSlowError(readinessRetryAt);
}

export function waitForDemoReady(): Promise<void> {
  if (flight) return flight.promise;
  const controller = new AbortController();
  const promise = poll(controller.signal);
  flight = { promise, controller };
  void promise.finally(() => {
    controller.abort();
    if (flight?.promise === promise) flight = undefined;
  }).catch(() => {});
  return promise;
}

function temporaryManualFailure(error: unknown): boolean {
  if (!axios.isAxiosError<unknown>(error)) return false;
  const data = error.response?.data;
  if (typeof data === 'object' && data !== null && 'error' in data
    && (data.error === 'BACKEND_NOT_CONFIGURED' || data.error === 'GATEWAY_NOT_CONFIGURED')) return false;
  const status = error.response?.status;
  return status !== undefined ? [408, 500, 502, 503, 504].includes(status)
    : ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(error.code ?? '');
}

/** One GET only. It never starts poll(), issues auth POSTs, or retries a response. */
export function checkDemoReadyOnce(): Promise<ManualReadinessResult> {
  if (manualFlight) return manualFlight.promise;
  if (flight) return Promise.reject(new DemoReadinessError());
  const controller = new AbortController();
  const promise = (async () => {
    try {
      const ready = await demoHttp.ready(controller.signal, DEMO_READY_REQUEST_MS);
      if (controller.signal.aborted) throw new DemoReadinessError();
      return { ready, retryAt: null };
    } catch (error) {
      if (controller.signal.aborted) throw new DemoReadinessError();
      const delay = demoRateLimitDelay(error, 'DEMO_READINESS_RATE_LIMITED');
      if (delay !== null) return { ready: false, retryAt: Date.now() + delay };
      if (temporaryManualFailure(error)) return { ready: false, retryAt: null };
      throw error;
    }
  })();
  manualFlight = { promise, controller };
  void promise.finally(() => {
    controller.abort();
    if (manualFlight?.promise === promise) manualFlight = undefined;
  }).catch(() => {});
  return promise;
}

export function cancelDemoReadiness(): void {
  const pending = flight;
  flight = undefined;
  pending?.controller.abort();
  const manual = manualFlight;
  manualFlight = undefined;
  manual?.controller.abort();
}
