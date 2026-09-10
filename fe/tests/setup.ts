import '@testing-library/jest-dom/vitest';

// jsdom has no native top-layer modal/popover state. Floating UI checks these
// exact selectors while opening the real Radix select. With nwsapi 2.2.27,
// native-state fallback recurses into jsdom.matches instead of returning false.
// Keep every ordinary selector on the original implementation.
const originalMatches = Element.prototype.matches;
Element.prototype.matches = function matches(selector: string): boolean {
  if (selector === ':modal' || selector === ':popover-open') return false;
  return originalMatches.call(this, selector);
};

// Minimal DOM APIs needed by the existing ChartPage and the actual Radix select.
if (!globalThis.PointerEvent) {
  class TestPointerEvent extends MouseEvent {
    readonly pointerId: number;
    readonly pointerType: string;
    readonly isPrimary: boolean;

    constructor(type: string, properties: PointerEventInit = {}) {
      super(type, properties);
      this.pointerId = properties.pointerId ?? 1;
      this.pointerType = properties.pointerType ?? 'mouse';
      this.isPrimary = properties.isPrimary ?? true;
    }
  }
  Object.defineProperty(globalThis, 'PointerEvent', { configurable: true, value: TestPointerEvent });
}

for (const name of ['scrollIntoView', 'setPointerCapture', 'releasePointerCapture'] as const) {
  if (!Element.prototype[name]) {
    Object.defineProperty(Element.prototype, name, { configurable: true, value() {} });
  }
}
if (!Element.prototype.hasPointerCapture) {
  Object.defineProperty(Element.prototype, 'hasPointerCapture', { configurable: true, value: () => false });
}

if (!globalThis.ResizeObserver) {
  class TestResizeObserver implements ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: TestResizeObserver });
}

if (!globalThis.IntersectionObserver) {
  class TestIntersectionObserver implements IntersectionObserver {
    readonly root = null;
    readonly rootMargin = '0px';
    readonly thresholds = [0];
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords(): IntersectionObserverEntry[] { return []; }
  }
  Object.defineProperty(globalThis, 'IntersectionObserver', { configurable: true, value: TestIntersectionObserver });
}
