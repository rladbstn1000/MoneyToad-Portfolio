import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ADVICE_GESTURE_IDLE_MS, installAdvicePaging, wheelPixels } from '../src/pages/adviceSectionPaging';

let clock: number;
let frames: Map<number, FrameRequestCallback>;
let frameId: number;
let dispose: (() => void) | undefined;
beforeEach(() => {
  clock = 0; frames = new Map(); frameId = 0;
  vi.spyOn(performance, 'now').mockImplementation(() => clock);
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => { frames.set(++frameId, callback); return frameId; }));
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => { frames.delete(id); }));
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: false }));
});
afterEach(() => { dispose?.(); dispose = undefined; document.body.replaceChildren(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

function fixture(longOverview = false) {
  const container = document.createElement('div');
  const overview = document.createElement('section');
  const title = document.createElement('h1'); title.tabIndex = -1;
  overview.append(title);
  const details = document.createElement('h2'); details.tabIndex = -1; details.style.scrollMarginTop = '112px';
  container.append(overview, details); document.body.append(container);
  const sizes = { viewport: 900, overview: longOverview ? 1500 : 900 };
  Object.defineProperty(container, 'clientHeight', { get: () => sizes.viewport });
  Object.defineProperty(container, 'scrollHeight', { get: () => sizes.overview + 2200 });
  const rect = (top: number, height: number) => ({ top, bottom: top + height, left: 0, right: 1000, width: 1000, height, x: 0, y: top, toJSON() { return {}; } });
  vi.spyOn(container, 'getBoundingClientRect').mockImplementation(() => rect(0, sizes.viewport));
  vi.spyOn(overview, 'getBoundingClientRect').mockImplementation(() => rect(-container.scrollTop, sizes.overview));
  vi.spyOn(details, 'getBoundingClientRect').mockImplementation(() => rect(sizes.overview + 60 - container.scrollTop, 30));
  const detailTop = () => sizes.overview + 60 - 112;
  const toDetails = vi.spyOn(details, 'scrollIntoView').mockImplementation(() => { container.scrollTop = detailTop(); });
  container.scrollTo = vi.fn((options?: ScrollToOptions | number, y?: number) => { container.scrollTop = typeof options === 'number' ? y ?? 0 : options?.top ?? container.scrollTop; });
  const paging = installAdvicePaging({ container, overview, details }); dispose = paging.dispose;
  function wheel(deltaY: number, options: WheelEventInit = {}, target: HTMLElement = container) {
    const event = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY, ...options }); target.dispatchEvent(event); return event;
  }
  function finish() { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback(clock)); }
  function nextGesture() { clock += ADVICE_GESTURE_IDLE_MS + 1; }
  function key(value: string, target: HTMLElement = container) { const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: value }); target.dispatchEvent(event); return event; }
  return { container, overview, details, title, sizes, paging, toDetails, detailTop, wheel, finish, nextGesture, key };
}

describe('advice section wheel intent and normal reading boundaries', () => {
  it.each([[12, 0, 12], [2, 1, 32], [1, 2, 900]])('normalizes delta %s mode %s into %s pixels', (delta, mode, expected) => {
    expect(wheelPixels(delta, mode, 16, 900)).toBe(expected);
  });
  it('ignores small noise then transitions once for accumulated deliberate input', () => {
    const f = fixture();
    expect(f.wheel(10).defaultPrevented).toBe(true); expect(f.toDetails).not.toHaveBeenCalled();
    f.wheel(10); expect(f.toDetails).not.toHaveBeenCalled();
    f.wheel(4); expect(f.toDetails).toHaveBeenCalledTimes(1); expect(f.details).toHaveFocus();
    f.finish();
    f.wheel(60); f.wheel(-60);
    expect(f.toDetails).toHaveBeenCalledTimes(1); expect(f.container.scrollTop).toBe(f.detailTop());
    f.nextGesture(); f.wheel(-24); f.finish();
    expect(f.container.scrollTop).toBe(0); expect(f.title).toHaveFocus();
  });
  it('does not accumulate unrelated input after gesture idle', () => {
    const f = fixture(); f.wheel(12); f.nextGesture(); f.wheel(12);
    expect(f.toDetails).not.toHaveBeenCalled(); f.wheel(12); expect(f.toDetails).toHaveBeenCalledTimes(1);
  });
  it('normalizes line and page wheel intent before threshold comparison', () => {
    const f = fixture(); f.wheel(2, { deltaMode: 1 }); f.finish();
    expect(f.toDetails).toHaveBeenCalledTimes(1);
    f.nextGesture(); f.wheel(-1, { deltaMode: 2 });
    expect(f.container.scrollTop).toBe(0);
  });
  it('leaves a tall overview readable until its end and requires a new gesture there', () => {
    const f = fixture(true);
    expect(f.wheel(80).defaultPrevented).toBe(false);
    f.container.scrollTop = 580;
    expect(f.wheel(40).defaultPrevented).toBe(true); expect(f.container.scrollTop).toBe(600);
    f.wheel(60); expect(f.toDetails).not.toHaveBeenCalled();
    f.nextGesture(); f.wheel(24); expect(f.toDetails).toHaveBeenCalledTimes(1);
  });
  it('reads long details naturally and stops upward inertia at their top before a fresh return gesture', () => {
    const f = fixture(); f.container.scrollTop = f.detailTop() + 500;
    expect(f.wheel(-80).defaultPrevented).toBe(false);
    f.container.scrollTop = f.detailTop() + 20;
    expect(f.wheel(-40).defaultPrevented).toBe(true); expect(f.container.scrollTop).toBe(f.detailTop());
    f.wheel(-200); expect(f.container.scrollTop).toBe(f.detailTop());
    f.nextGesture(); f.wheel(-24); expect(f.container.scrollTop).toBe(0);
  });
  it('holds remaining inertia when normal reading lands exactly on a section boundary', () => {
    const f = fixture(true);
    f.wheel(80); f.container.scrollTop = 600;
    expect(f.wheel(24).defaultPrevented).toBe(true); expect(f.toDetails).not.toHaveBeenCalled();
    f.nextGesture(); f.wheel(24); f.finish();
    f.container.scrollTop = f.detailTop() + 200; f.nextGesture(); f.wheel(-80);
    f.container.scrollTop = f.detailTop();
    expect(f.wheel(-24).defaultPrevented).toBe(true); expect(f.container.scrollTop).toBe(f.detailTop());
    f.nextGesture(); f.wheel(-24); expect(f.container.scrollTop).toBe(0);
  });
  it('does not intercept horizontal gestures, pinch zoom or input selection', () => {
    const f = fixture(); const input = document.createElement('input'); f.container.append(input);
    expect(f.wheel(50, { deltaX: 100 }).defaultPrevented).toBe(false);
    expect(f.wheel(50, { ctrlKey: true }).defaultPrevented).toBe(false);
    expect(f.wheel(50, {}, input).defaultPrevented).toBe(false);
    expect(f.toDetails).not.toHaveBeenCalled();
  });
  it('keeps modal and nested scroll content independent from section paging', () => {
    const f = fixture();
    const modal = document.createElement('div'); modal.setAttribute('role', 'dialog'); f.container.append(modal);
    const scroller = document.createElement('div'); scroller.style.overflowY = 'auto';
    Object.defineProperty(scroller, 'clientHeight', { value: 200 }); Object.defineProperty(scroller, 'scrollHeight', { value: 400 }); f.container.append(scroller);
    expect(f.wheel(100, {}, modal).defaultPrevented).toBe(false);
    expect(f.wheel(100, {}, scroller).defaultPrevented).toBe(false);
    expect(f.toDetails).not.toHaveBeenCalled();
  });
  it('shares CTA and PageDown/PageUp transitions while leaving form keys alone', () => {
    const f = fixture(); f.paging.showDetails(); f.finish(); expect(f.toDetails).toHaveBeenCalledTimes(1);
    expect(f.key('PageUp', f.details).defaultPrevented).toBe(true); f.finish(); expect(f.title).toHaveFocus();
    expect(f.key('PageDown', document.body).defaultPrevented).toBe(true); f.finish(); expect(f.toDetails).toHaveBeenCalledTimes(2);
    const input = document.createElement('input'); f.container.append(input);
    expect(f.key('PageUp', input).defaultPrevented).toBe(false);
    expect(f.key('Tab', f.details).defaultPrevented).toBe(false);
  });
  it('accepts the opposite keyboard move as soon as native scrolling arrives before its observer frame', () => {
    const f = fixture(); vi.mocked(window.matchMedia).mockReturnValue({ matches: true } as MediaQueryList);
    f.key('PageDown', document.body);
    expect(f.container.scrollTop).toBe(f.detailTop()); expect(frames.size).toBe(1);
    // Do not flush RAF: geometry already proves completion of the native move.
    f.key('PageUp', f.details);
    expect(f.container.scrollTop).toBe(0); expect(f.title).toHaveFocus();
    f.key('PageDown', f.title);
    expect(f.container.scrollTop).toBe(f.detailTop()); expect(f.toDetails).toHaveBeenCalledTimes(2);
    expect(frames.size).toBe(1);
  });
  it('recomputes the anchored detail destination on resize and honors reduced motion', () => {
    const f = fixture(); vi.mocked(window.matchMedia).mockReturnValue({ matches: true } as MediaQueryList);
    f.paging.showDetails(); f.finish(); expect(f.toDetails).toHaveBeenLastCalledWith({ behavior: 'auto', block: 'start' });
    f.sizes.overview = 1100; window.dispatchEvent(new Event('resize')); f.finish();
    expect(f.container.scrollTop).toBe(f.detailTop());
    expect(f.toDetails).toHaveBeenLastCalledWith({ behavior: 'instant', block: 'start' });
  });
  it('removes scoped listeners and pending animation observation on dispose', () => {
    const f = fixture(); f.paging.showDetails(); expect(frames.size).toBe(1); f.paging.dispose();
    expect(frames.size).toBe(0);
    f.toDetails.mockClear(); f.container.scrollTop = 0;
    expect(f.wheel(80).defaultPrevented).toBe(false); f.key('PageDown'); window.dispatchEvent(new Event('resize'));
    expect(f.toDetails).not.toHaveBeenCalled();
  });
});
