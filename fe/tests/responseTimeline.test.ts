import { afterEach, describe, expect, it, vi } from 'vitest';
import { createResponseTimeline, normalizedApiPath, type TimelineSource } from '../e2e/responseTimeline';

class FakeRequest {
  private readonly path: string;
  private readonly verb: string;
  constructor(path: string, verb = 'GET') { this.path = path; this.verb = verb; }
  method() { return this.verb; }
  url() { return `http://127.0.0.1${this.path}`; }
}
class FakeResponse {
  readonly json = vi.fn(async () => [{ date: '2026-10', totalAmount: 908000, leaked: this.leaked }]);
  private readonly req: FakeRequest;
  private readonly code: number;
  readonly leaked: boolean;
  constructor(req: FakeRequest, code = 200, leaked = false) { this.req = req; this.code = code; this.leaked = leaked; }
  request() { return this.req; }
  status() { return this.code; }
}
class Source implements TimelineSource<FakeRequest, FakeResponse> {
  readonly requests = new Set<(request: FakeRequest) => void>();
  readonly responses = new Set<(response: FakeResponse) => void>();
  on(event: 'request', listener: (request: FakeRequest) => void): void;
  on(event: 'response', listener: (response: FakeResponse) => void): void;
  on(event: 'request' | 'response', listener: ((request: FakeRequest) => void) | ((response: FakeResponse) => void)) {
    if (event === 'request') this.requests.add(listener as (request: FakeRequest) => void);
    else this.responses.add(listener as (response: FakeResponse) => void);
  }
  off(event: 'request', listener: (request: FakeRequest) => void): void;
  off(event: 'response', listener: (response: FakeResponse) => void): void;
  off(event: 'request' | 'response', listener: ((request: FakeRequest) => void) | ((response: FakeResponse) => void)) {
    if (event === 'request') this.requests.delete(listener as (request: FakeRequest) => void);
    else this.responses.delete(listener as (response: FakeResponse) => void);
  }
  start(request: FakeRequest) { this.requests.forEach(listener => listener(request)); }
  respond(response: FakeResponse) { this.responses.forEach(listener => listener(response)); }
}
const disposers: (() => void)[] = [];
function setup(maxRecords?: number) {
  const source = new Source();
  const timeline = createResponseTimeline<FakeRequest, FakeResponse>(source, () => 'chart', { maxRecords });
  disposers.push(() => timeline.dispose());
  const patch = new FakeRequest('/api/transactions/1/category', 'PATCH');
  const boundary = () => { source.start(patch); const result = new FakeResponse(patch); source.respond(result); return timeline.responseOrder(result); };
  return { source, timeline, boundary };
}
const annual = (after: number) => ({ method: 'GET', path: '/api/transactions', status: 200, after });
afterEach(() => { disposers.splice(0).forEach(dispose => dispose()); vi.useRealTimers(); });

describe('post-PATCH request-start correlation', () => {
  it('selects only new leaked=false annual in the exact stale response race', async () => {
    const { source, timeline, boundary } = setup();
    const old = new FakeRequest('/api/transactions'); source.start(old);
    const after = boundary();
    const selected = timeline.waitForResponseStartedAfter(annual(after));
    const stale = new FakeResponse(old, 200, true); source.respond(stale);
    const fresh = new FakeRequest('/api/transactions'); source.start(fresh);
    const current = new FakeResponse(fresh, 200, false); source.respond(current);
    // A response-arrival-only selector chooses stale here and must fail this expectation.
    expect(await (await selected).json()).toEqual([{ date: '2026-10', totalAmount: 908000, leaked: false }]);
    expect(await selected).toBe(current); expect(stale.json).not.toHaveBeenCalled();
    expect(timeline.records().map(row => row.responseOrder ?? row.requestStartOrder)).toEqual([1, 2, 3, 4, 5, 6]);
  });
  it('also rejects the observed prior-run ordering with stale response before PATCH response', async () => {
    const { source, timeline } = setup();
    const old = new FakeRequest('/api/transactions'); source.start(old);
    const patch = new FakeRequest('/api/transactions/1/category', 'PATCH'); source.start(patch);
    const stale = new FakeResponse(old, 200, true); source.respond(stale);
    const patched = new FakeResponse(patch); source.respond(patched);
    const after = timeline.responseOrder(patched);
    const pending = timeline.waitForResponseStartedAfter(annual(after));
    const fresh = new FakeRequest('/api/transactions'); source.start(fresh); const current = new FakeResponse(fresh); source.respond(current);
    expect(await pending).toBe(current); expect(await (await pending).json()).toEqual([{ date: '2026-10', totalAmount: 908000, leaked: false }]);
    expect(stale.json).not.toHaveBeenCalled();
  });
  it('finds a fast refetch already completed before waiter registration', async () => {
    const { source, timeline, boundary } = setup(); const after = boundary();
    const request = new FakeRequest('/api/transactions'); source.start(request); const response = new FakeResponse(request); source.respond(response);
    expect(await timeline.waitForResponseStartedAfter(annual(after))).toBe(response);
  });
  it('waits for a later response without sending requests or reading its body', async () => {
    const { source, timeline, boundary } = setup(); const after = boundary();
    const pending = timeline.waitForResponseStartedAfter(annual(after));
    expect(timeline.records()).toHaveLength(2);
    const request = new FakeRequest('/api/transactions'); source.start(request); const response = new FakeResponse(request); source.respond(response);
    expect(await pending).toBe(response); expect(response.json).not.toHaveBeenCalled();
  });
  it('excludes stale responses already cached when wait is registered', async () => {
    const { source, timeline, boundary } = setup(); const old = new FakeRequest('/api/transactions'); source.start(old); const after = boundary();
    const stale = new FakeResponse(old, 200, true); source.respond(stale);
    const pending = timeline.waitForResponseStartedAfter(annual(after));
    const request = new FakeRequest('/api/transactions'); source.start(request); const response = new FakeResponse(request); source.respond(response);
    expect(await pending).toBe(response); expect(stale.json).not.toHaveBeenCalled();
  });
  it('does not infer an unobserved request start from a late response', async () => {
    const { source, timeline, boundary } = setup(); const after = boundary(); const pending = timeline.waitForResponseStartedAfter(annual(after));
    source.respond(new FakeResponse(new FakeRequest('/api/transactions'), 200, true));
    expect(timeline.records().at(-1)?.requestStartOrder).toBeNull();
    const request = new FakeRequest('/api/transactions'); source.start(request); const response = new FakeResponse(request); source.respond(response);
    expect(await pending).toBe(response);
  });
  it('requires matching method path and success status', async () => {
    const { source, timeline, boundary } = setup(); const after = boundary(); const pending = timeline.waitForResponseStartedAfter(annual(after));
    for (const [path, method, status] of [['/api/budgets/2026/10', 'GET', 200], ['/api/transactions', 'POST', 200], ['/api/transactions', 'GET', 503]] as const) {
      const request = new FakeRequest(path, method); source.start(request); source.respond(new FakeResponse(request, status));
    }
    const request = new FakeRequest('/api/transactions'); source.start(request); const response = new FakeResponse(request); source.respond(response);
    expect(await pending).toBe(response);
  });
  it('correlates annual monthly and category invalidations independently', async () => {
    const { source, timeline, boundary } = setup(); const after = boundary();
    const paths = ['/api/transactions', '/api/transactions/2026/10', '/api/transactions/2026/10/categories'];
    const waits = paths.map(path => timeline.waitForResponseStartedAfter({ ...annual(after), path: normalizedApiPath(`http://127.0.0.1${path}`)! }));
    const expected = paths.map(path => { const request = new FakeRequest(path); source.start(request); const response = new FakeResponse(request); source.respond(response); return response; });
    expect(await Promise.all(waits)).toEqual(expected);
    expect(paths.every(path => timeline.hasRequestStartedAfter({ ...annual(after), path: normalizedApiPath(`http://127.0.0.1${path}`)! }))).toBe(true);
  });
  it('preserves the original order for duplicate object observations', () => {
    const { source, timeline } = setup(); const request = new FakeRequest('/api/transactions');
    source.start(request); source.start(request); const response = new FakeResponse(request); source.respond(response); source.respond(response);
    expect(timeline.records()).toHaveLength(2); expect(timeline.records()[1]).toMatchObject({ requestStartOrder: 1, responseOrder: 2 });
  });
  it('times out finitely and does not synthesize a response', async () => {
    vi.useFakeTimers(); const { timeline, boundary } = setup(); const after = boundary();
    const pending = timeline.waitForResponseStartedAfter({ ...annual(after), timeoutMs: 20 });
    const rejected = expect(pending).rejects.toThrow('E2E_TIMELINE_WAIT_TIMEOUT');
    await vi.advanceTimersByTimeAsync(20); await rejected; expect(vi.getTimerCount()).toBe(0);
  });
  it('disposes listeners and rejects pending waits without leaving timers', async () => {
    vi.useFakeTimers(); const { source, timeline, boundary } = setup(); const after = boundary();
    const pending = timeline.waitForResponseStartedAfter(annual(after));
    const rejected = expect(pending).rejects.toThrow('E2E_TIMELINE_DISPOSED'); timeline.dispose(); await rejected;
    expect(source.requests.size + source.responses.size).toBe(0); expect(vi.getTimerCount()).toBe(0);
    await expect(timeline.waitForResponseStartedAfter(annual(after))).rejects.toThrow('E2E_TIMELINE_DISPOSED');
  });
  it('fails closed at the finite observation capacity', async () => {
    const { source, timeline, boundary } = setup(2); const after = boundary();
    const pending = timeline.waitForResponseStartedAfter(annual(after)); const rejected = expect(pending).rejects.toThrow('E2E_TIMELINE_CAPACITY');
    source.start(new FakeRequest('/api/transactions')); await rejected;
    expect(timeline.records()).toHaveLength(2);
    await expect(timeline.waitForResponseStartedAfter(annual(after))).rejects.toThrow('E2E_TIMELINE_CAPACITY');
  });
  it('records only safe metadata and returns copies', () => {
    const { source, timeline } = setup(); const request = new FakeRequest('/api/transactions/123/10?hidden=opaque#fragment'); source.start(request); source.respond(new FakeResponse(request));
    expect(timeline.records()).toEqual([
      { method: 'GET', path: '/api/transactions/:n/:n', requestStartOrder: 1, phase: 'chart' },
      { method: 'GET', path: '/api/transactions/:n/:n', requestStartOrder: 1, responseOrder: 2, status: 200, phase: 'chart' },
    ]);
    const copy = timeline.records(); copy[0].path = '/changed'; expect(timeline.records()[0].path).toBe('/api/transactions/:n/:n');
    expect(normalizedApiPath('http://127.0.0.1/api/unrecognized-value')).toBe('/api/other');
    expect(normalizedApiPath('http://127.0.0.1/assets/app.js')).toBeNull();
  });
  it('rejects unobserved response boundaries and invalid waits', async () => {
    const { timeline } = setup(); expect(() => timeline.responseOrder(new FakeResponse(new FakeRequest('/api/transactions')))).toThrow('E2E_TIMELINE_RESPONSE_UNOBSERVED');
    await expect(timeline.waitForResponseStartedAfter(annual(0))).rejects.toThrow('E2E_TIMELINE_WAIT_CONFIGURATION');
    await expect(timeline.waitForResponseStartedAfter({ ...annual(1), timeoutMs: Infinity })).rejects.toThrow('E2E_TIMELINE_WAIT_CONFIGURATION');
  });
});
