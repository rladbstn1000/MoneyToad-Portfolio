import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ScrollLandingPage from '../src/pages/ScrollLandingPage';

beforeEach(() => { vi.useFakeTimers(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); });

function mount() {
  const view = render(<StrictMode><MemoryRouter><ScrollLandingPage /></MemoryRouter></StrictMode>);
  const landing = view.container.querySelector('.dk-landing');
  if (!landing) throw new Error('Landing did not mount');
  return { ...view, landing };
}
const expectPage = (page: number) => expect(screen.getByRole('button', { name: `Go to page ${page}` })).toHaveClass('active');
const unlock = () => act(() => vi.advanceTimersByTime(700));

describe('landing lint refactor behavior', () => {
  it('keeps the 700ms lock and wheel/key boundaries under StrictMode', () => {
    const { landing } = mount();
    fireEvent.keyDown(window, { key: 'ArrowUp' }); expectPage(1);
    fireEvent.wheel(landing, { deltaY: 10 }); expectPage(2);
    fireEvent.keyDown(window, { key: 'ArrowDown' }); expectPage(2);
    act(() => vi.advanceTimersByTime(699));
    fireEvent.wheel(landing, { deltaY: 10 }); expectPage(2);
    act(() => vi.advanceTimersByTime(1));
    fireEvent.keyDown(window, { key: 'ArrowDown' }); expectPage(3);
    unlock(); fireEvent.wheel(landing, { deltaY: 10 }); expectPage(4);
    unlock(); fireEvent.keyDown(window, { key: 'ArrowDown' }); expectPage(4);
    fireEvent.wheel(landing, { deltaY: -10 }); expectPage(3);
  });

  it('preserves touch threshold, direction and dot navigation', () => {
    const { landing } = mount();
    fireEvent.touchStart(landing, { touches: [{ clientY: 100 }] });
    fireEvent.touchEnd(landing, { changedTouches: [{ clientY: 50 }] }); expectPage(1);
    fireEvent.touchEnd(landing, { changedTouches: [{ clientY: 49 }] }); expectPage(2);
    unlock();
    fireEvent.touchStart(landing, { touches: [{ clientY: 50 }] });
    fireEvent.touchEnd(landing, { changedTouches: [{ clientY: 101 }] }); expectPage(1);
    unlock(); fireEvent.click(screen.getByRole('button', { name: 'Go to page 4' })); expectPage(4);
  });

  it('removes every registered event callback on unmount and remounts without duplicates', () => {
    const addWindow = vi.spyOn(window, 'addEventListener');
    const removeWindow = vi.spyOn(window, 'removeEventListener');
    const addElement = vi.spyOn(HTMLElement.prototype, 'addEventListener');
    const removeElement = vi.spyOn(HTMLElement.prototype, 'removeEventListener');
    const view = mount();
    fireEvent.wheel(view.landing, { deltaY: 10 }); unlock();
    view.unmount();
    for (const [event, callback] of addWindow.mock.calls.filter(([event]) => event === 'keydown')) {
      expect(removeWindow.mock.calls.some(([name, listener]) => name === event && listener === callback)).toBe(true);
    }
    // React also delegates these events on its root; only this page owns the
    // native callbacks attached directly to the landing element.
    const registrations = addElement.mock.calls.filter(([event], index) =>
      addElement.mock.contexts[index] === view.landing && ['wheel', 'touchstart', 'touchend'].includes(event));
    expect(registrations.length).toBeGreaterThan(0);
    for (const [event, callback] of registrations) {
      expect(removeElement.mock.calls.some(([name, listener]) => name === event && listener === callback)).toBe(true);
    }
    mount(); fireEvent.keyDown(window, { key: 'ArrowDown' }); expectPage(2);
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();
  });
});
