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
  it('places story copy inside the exact-ratio image scene instead of a separate column', () => {
    const { container } = mount();
    const layout = container.querySelector('.dk-page .dk-story-layout');
    const scene = layout?.querySelector('.dk-story-scene');
    const image = screen.getByRole('img', { name: '콩쥐의 꿈 이야기 그림' });
    const copy = layout?.querySelector('.dk-content');
    expect(scene).toContainElement(image);
    expect(image).toHaveAttribute('src', '/landing/landing1.webp');
    expect(image).toHaveAttribute('width', '1549');
    expect(image).toHaveAttribute('height', '1033');
    expect(scene).toContainElement(copy as HTMLElement);
    expect(scene?.nextElementSibling).toBeNull();
    expect(copy?.parentElement).toBe(scene);
    expect(container.querySelector('.dk-page--1')).toHaveStyle({ '--scene-ratio': String(1549 / 1033) });
    expect(copy).not.toContainElement(image);
    expect(container.querySelectorAll('.dk-page')).toHaveLength(4);
    expect(screen.getAllByRole('button', { name: /Go to page/ })).toHaveLength(4);
  });
  it('retains all four stories with their own image dimensions and overlay anchors', () => {
    const { container } = mount();
    const stories = [
      ['콩쥐의 꿈', 1549, 1033], ['두꺼비를 만나다', 1493, 995],
      ['장독대의 비밀', 2048, 1365], ['콩쥐의 장독대', 1280, 853],
    ] as const;
    stories.forEach(([title, width, height], index) => {
      if (index > 0) { unlock(); fireEvent.click(screen.getByRole('button', { name: `Go to page ${index + 1}` })); }
      const image = screen.getByRole('img', { name: `${title} 이야기 그림` });
      const scene = image.closest('.dk-story-scene');
      expect(image).toHaveAttribute('width', String(width));
      expect(image).toHaveAttribute('height', String(height));
      expect(scene).toContainElement(screen.getByRole('heading', { name: title }));
      expect(scene?.querySelector('.dk-desc')).not.toBeEmptyDOMElement();
      expect(container.querySelector(`.dk-page--${index + 1}`)).toHaveStyle({ '--scene-ratio': String(width / height) });
      expect(scene?.querySelector('.dk-content')).toHaveClass('show');
    });
    expect(container.querySelector('.dk-counter')).toHaveTextContent('04/04');
  });
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
