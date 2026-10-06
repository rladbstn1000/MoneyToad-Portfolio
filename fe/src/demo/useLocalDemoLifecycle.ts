import { useEffect } from 'react';
import { useLocalDemoStore } from './localDemoStore';

/** A restored document is a fresh visit. Tab visibility changes are not visits. */
export function useLocalDemoLifecycle(): void {
  useEffect(() => {
    const restore = (event: PageTransitionEvent) => {
      if (event.persisted) useLocalDemoStore.getState().reset();
    };
    window.addEventListener('pageshow', restore);
    return () => window.removeEventListener('pageshow', restore);
  }, []);
}
