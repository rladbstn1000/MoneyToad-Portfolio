/** E2E observation only. Request/Response contents never enter serializable records. */
export interface TimelineRequest { method(): string; url(): string }
export interface TimelineResponse<Request extends TimelineRequest> { request(): Request; status(): number }
export interface TimelineSource<Request extends TimelineRequest, Response extends TimelineResponse<Request>> {
  on(event: 'request', listener: (request: Request) => void): unknown;
  on(event: 'response', listener: (response: Response) => void): unknown;
  off(event: 'request', listener: (request: Request) => void): unknown;
  off(event: 'response', listener: (response: Response) => void): unknown;
}
export type NetworkRecord = {
  method: string; path: string; phase: string;
  requestStartOrder: number | null; responseOrder?: number; status?: number;
};
type RequestMatch = { method: string; path: string; after: number };
type ResponseMatch = RequestMatch & { status: number; timeoutMs?: number };

/** Only known API shapes are exposed. Host, query, fragments and numeric IDs are omitted. */
export function normalizedApiPath(url: string): string | null {
  let path: string;
  try { path = new URL(url).pathname; } catch { return null; }
  if (!path.startsWith('/api/')) return null;
  if (/^\/api\/auth\/demo\/(ready|login|reissue|session|logout)$/.test(path)
    || /^\/api\/(users|transactions|budgets|cards|test)$/.test(path)
    || /^\/api\/transactions\/\d+\/(?:\d+(?:\/categories)?|category)$/.test(path)
    || /^\/api\/budgets\/\d+\/\d+$/.test(path)) return path.replace(/\/\d+(?=\/|$)/g, '/:n');
  return '/api/other';
}

export function createResponseTimeline<Request extends TimelineRequest, Response extends TimelineResponse<Request>>(
  source: TimelineSource<Request, Response>, phase: () => string,
  options: { maxRecords?: number } = {},
) {
  const maxRecords = options.maxRecords ?? 4096;
  if (!Number.isSafeInteger(maxRecords) || maxRecords < 1 || maxRecords > 4096) throw new Error('E2E_TIMELINE_CAPACITY_CONFIGURATION');
  let sequence = 0, disposed = false, failure: Error | undefined;
  const requestStartOrder = new WeakMap<Request, number>();
  const responseOrder = new WeakMap<Response, number>();
  const safeRecords: NetworkRecord[] = [];
  const responses: { value: Response; record: NetworkRecord }[] = [];
  const waiters = new Set<{ match: ResponseMatch; resolve(value: Response): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }>();
  function finish(waiter: typeof waiters extends Set<infer W> ? W : never, value?: Response, error?: Error) {
    clearTimeout(waiter.timer); waiters.delete(waiter);
    if (error) waiter.reject(error); else waiter.resolve(value!);
  }
  function fail(code: string) {
    failure ??= new Error(code);
    for (const waiter of [...waiters]) finish(waiter, undefined, failure);
  }
  function add(record: NetworkRecord): boolean {
    if (safeRecords.length >= maxRecords) { fail('E2E_TIMELINE_CAPACITY'); return false; }
    safeRecords.push(record); return true;
  }
  function matches(record: NetworkRecord, match: ResponseMatch): boolean {
    return record.requestStartOrder !== null && record.requestStartOrder > match.after
      && record.method === match.method && record.path === match.path && record.status === match.status;
  }
  const onRequest = (request: Request) => {
    if (disposed || failure || requestStartOrder.has(request)) return;
    const order = ++sequence; requestStartOrder.set(request, order);
    const path = normalizedApiPath(request.url());
    if (path !== null) add({ method: request.method(), path, requestStartOrder: order, phase: phase() });
  };
  const onResponse = (response: Response) => {
    if (disposed || failure || responseOrder.has(response)) return;
    const order = ++sequence; responseOrder.set(response, order);
    const request = response.request(), path = normalizedApiPath(request.url());
    if (path === null) return;
    const record: NetworkRecord = { method: request.method(), path, status: response.status(),
      requestStartOrder: requestStartOrder.get(request) ?? null, responseOrder: order, phase: phase() };
    if (!add(record)) return;
    responses.push({ value: response, record });
    for (const waiter of [...waiters]) if (matches(record, waiter.match)) finish(waiter, response);
  };
  source.on('request', onRequest); source.on('response', onResponse);
  return {
    records(): NetworkRecord[] { return safeRecords.map(record => ({ ...record })); },
    responseOrder(response: Response): number {
      if (failure) throw failure;
      const order = responseOrder.get(response);
      if (order === undefined) throw new Error('E2E_TIMELINE_RESPONSE_UNOBSERVED');
      return order;
    },
    hasRequestStartedAfter(match: RequestMatch): boolean {
      return safeRecords.some(record => record.responseOrder === undefined && record.requestStartOrder !== null
        && record.requestStartOrder > match.after && record.method === match.method && record.path === match.path);
    },
    waitForResponseStartedAfter(match: ResponseMatch): Promise<Response> {
      if (disposed) return Promise.reject(new Error('E2E_TIMELINE_DISPOSED'));
      if (failure) return Promise.reject(failure);
      const timeoutMs = match.timeoutMs ?? 15_000;
      if (!Number.isSafeInteger(match.after) || match.after < 1 || !Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 60_000)
        return Promise.reject(new Error('E2E_TIMELINE_WAIT_CONFIGURATION'));
      const cached = responses.find(entry => matches(entry.record, match));
      if (cached) return Promise.resolve(cached.value);
      if (waiters.size >= 32) return Promise.reject(new Error('E2E_TIMELINE_WAITER_CAPACITY'));
      // Lookup and waiter registration are synchronous. A future event cannot be
      // lost between them; responses received before this call remain in the cache.
      return new Promise<Response>((resolve, reject) => {
        const waiter = { match, resolve, reject, timer: setTimeout(() => finish(waiter, undefined, new Error('E2E_TIMELINE_WAIT_TIMEOUT')), timeoutMs) };
        waiters.add(waiter);
      });
    },
    dispose(): void {
      if (disposed) return;
      disposed = true; source.off('request', onRequest); source.off('response', onResponse);
      for (const waiter of [...waiters]) finish(waiter, undefined, new Error('E2E_TIMELINE_DISPOSED'));
      responses.length = 0;
    },
  };
}
