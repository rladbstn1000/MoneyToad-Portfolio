import { useCallback, useEffect, useRef } from 'react';
import type { RefObject } from 'react';

type Section = 'overview' | 'details';
type PagingElements = { container: HTMLElement; overview: HTMLElement; details: HTMLElement };

// Initial interaction values, not measured device-specific optimal settings.
export const ADVICE_WHEEL_THRESHOLD = 24;
export const ADVICE_GESTURE_IDLE_MS = 200;
const EDGE_TOLERANCE = 3;

export function wheelPixels(delta: number, mode: number, lineHeight: number, pageHeight: number) {
  return delta * (mode === 1 ? lineHeight : mode === 2 ? pageHeight : 1);
}

function nestedControl(target: EventTarget | null, container: HTMLElement, keyboard: boolean) {
  if (!(target instanceof Element)) return false;
  if (target.closest('[role="dialog"], input, select, textarea, [contenteditable="true"]')) return true;
  if (keyboard && target.closest('button, a, [role="button"]')) return true;
  for (let element: Element | null = target; element && element !== container; element = element.parentElement) {
    if (element instanceof HTMLElement) {
      const style = getComputedStyle(element);
      if (/auto|scroll/.test(style.overflowY) && element.scrollHeight > element.clientHeight + 1) return true;
    }
  }
  return false;
}

/** Scoped to the advice scroller. No document wheel listener or data mutation. */
export function installAdvicePaging({ container, overview, details }: PagingElements) {
  let lastInput = -Infinity;
  let accumulated = 0;
  let direction = 0;
  let consumed = false;
  let eligible: Section | null = null;
  let transition: Section | null = null;
  let frame: number | null = null;
  let disposed = false;
  const detailTop = () => {
    const margin = Number.parseFloat(getComputedStyle(details).scrollMarginTop) || 0;
    return Math.min(container.scrollHeight - container.clientHeight,
      Math.max(0, details.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop - margin));
  };
  const overviewEnd = () => Math.max(0,
    overview.getBoundingClientRect().bottom - container.getBoundingClientRect().top + container.scrollTop - container.clientHeight);
  let previousDetailTop = detailTop();
  const targetTop = (section: Section) => section === 'overview' ? 0 : detailTop();
  const stopFrame = () => { if (frame !== null) cancelAnimationFrame(frame); frame = null; };
  const move = (section: Section, focus = true, immediate = false) => {
    if (disposed) return;
    stopFrame();
    transition = section;
    consumed = true;
    previousDetailTop = detailTop();
    const element = section === 'overview' ? overview.querySelector<HTMLElement>('h1') : details;
    if (focus) element?.focus({ preventScroll: true });
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (section === 'details') details.scrollIntoView({ behavior: immediate ? 'instant' : reduced ? 'auto' : 'smooth', block: 'start' });
    else container.scrollTo({ top: 0, behavior: immediate ? 'instant' : reduced ? 'auto' : 'smooth' });
    const started = performance.now();
    const observe = () => {
      frame = null;
      if (disposed) return;
      if (Math.abs(container.scrollTop - targetTop(section)) <= EDGE_TOLERANCE || performance.now() - started > 1800) {
        transition = null;
        return;
      }
      frame = requestAnimationFrame(observe);
    };
    frame = requestAnimationFrame(observe);
  };
  const pickSection = (delta: number): Section | null => {
    const top = container.scrollTop;
    const detail = detailTop();
    if (delta > 0 && top < detail - EDGE_TOLERANCE && top >= overviewEnd() - EDGE_TOLERANCE) return 'details';
    if (delta < 0 && Math.abs(top - detail) <= EDGE_TOLERANCE) return 'overview';
    return null;
  };
  const holdBoundary = (delta: number) => {
    const top = container.scrollTop;
    const detail = detailTop();
    const end = overviewEnd();
    let boundary: number | null = null;
    if (delta < 0 && top > detail + EDGE_TOLERANCE && top + delta < detail) boundary = detail;
    if (delta > 0 && top < end - EDGE_TOLERANCE && top + delta > end) boundary = end;
    if (boundary === null) return false;
    container.scrollTo({ top: boundary, behavior: 'instant' });
    consumed = true;
    return true;
  };
  const transitionPending = () => {
    // Native scrolling may reach its destination before the observation frame
    // runs (especially with reduced motion). A completed move must not swallow
    // the next keyboard command or a newly separated wheel gesture.
    if (transition && Math.abs(container.scrollTop - targetTop(transition)) <= EDGE_TOLERANCE) {
      transition = null;
      stopFrame();
    }
    return transition !== null;
  };
  const onWheel = (event: WheelEvent) => {
    if (event.ctrlKey || Math.abs(event.deltaX) > Math.abs(event.deltaY) || !event.deltaY || nestedControl(event.target, container, false)) return;
    const line = Number.parseFloat(getComputedStyle(container).lineHeight) || 16;
    const delta = wheelPixels(event.deltaY, event.deltaMode, line, container.clientHeight);
    const now = performance.now();
    if (now - lastInput > ADVICE_GESTURE_IDLE_MS) {
      accumulated = 0;
      direction = Math.sign(delta);
      consumed = false;
      eligible = pickSection(delta);
    }
    lastInput = now;
    if (transitionPending() || consumed) { event.preventDefault(); return; }
    // A gesture that began inside readable content can reach its edge, but its
    // remaining inertia cannot also switch sections. The next gesture may.
    if (!eligible) {
      if (pickSection(delta) || holdBoundary(delta)) { consumed = true; event.preventDefault(); }
      return;
    }
    event.preventDefault();
    if (direction !== Math.sign(delta)) { accumulated = 0; eligible = null; return; }
    accumulated += Math.abs(delta);
    if (accumulated >= ADVICE_WHEEL_THRESHOLD) move(eligible);
  };
  const onKey = (event: KeyboardEvent) => {
    // A newly entered page may still have body focus. Never handle a key from
    // another page/portal; form and modal controls retain their native keys.
    if (event.target instanceof Node && event.target !== document.body && !container.contains(event.target)) return;
    if (!['PageDown', 'PageUp'].includes(event.key) || event.ctrlKey || event.metaKey || event.altKey || nestedControl(event.target, container, true)) return;
    if (transitionPending()) { event.preventDefault(); return; }
    const delta = event.key === 'PageDown' ? container.clientHeight : -container.clientHeight;
    const next = pickSection(delta);
    if (next) { event.preventDefault(); move(next); }
    else if (holdBoundary(delta)) event.preventDefault();
  };
  const onResize = () => {
    const anchored = Math.abs(container.scrollTop - previousDetailTop) <= EDGE_TOLERANCE;
    const current = transition;
    previousDetailTop = detailTop();
    if (current) move(current, false, true);
    else if (anchored) move('details', false, true);
  };
  container.addEventListener('wheel', onWheel, { passive: false });
  document.addEventListener('keydown', onKey);
  window.addEventListener('resize', onResize);
  const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(onResize);
  resize?.observe(container); resize?.observe(overview);
  return {
    showDetails: () => move('details'),
    dispose: () => {
      disposed = true; stopFrame(); resize?.disconnect();
      container.removeEventListener('wheel', onWheel);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', onResize);
    },
  };
}

export function useAdviceSectionPaging(container: RefObject<HTMLDivElement | null>,
  overview: RefObject<HTMLElement | null>, details: RefObject<HTMLHeadingElement | null>, enabled: boolean) {
  const controller = useRef<ReturnType<typeof installAdvicePaging> | null>(null);
  useEffect(() => {
    if (!enabled || !container.current || !overview.current || !details.current) return;
    const paging = installAdvicePaging({ container: container.current, overview: overview.current, details: details.current });
    controller.current = paging;
    return () => { paging.dispose(); controller.current = null; };
  }, [container, overview, details, enabled]);
  return useCallback(() => controller.current?.showDetails(), []);
}
