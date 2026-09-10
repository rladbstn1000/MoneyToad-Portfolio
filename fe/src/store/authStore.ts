import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";
import type { StateCreator } from "zustand";
import { authMode } from "../auth/authMode";

export type AuthState = {
  accessToken: string | null;
  status: 'restoring' | 'authenticated' | 'anonymous' | 'unavailable';
  operation: 'login' | 'restore' | 'refresh' | 'logout' | null;
  generation: number;
  revision: number;
  expiresAt: number | null;
  message: string | null;
  recovery: 'restore' | 'logout';
  setAccessToken: (t: string | null) => void;
  clear: () => void;
};

const initial: StateCreator<AuthState> = (set) => ({
  accessToken: null, status: authMode === 'demo' ? 'restoring' : 'anonymous',
  operation: null, generation: 0, revision: 0, expiresAt: null,
  message: null, recovery: 'restore',
  setAccessToken: (accessToken) => set({ accessToken }),
  clear: () => set(state => ({ accessToken: null,
    ...(authMode === 'demo' ? { status: 'anonymous' as const, expiresAt: null,
      generation: state.generation + 1 } : {}) })),
});

// The demo branch never constructs the OAuth storage adapter or hydrates it.
export const useAuthStore = authMode === 'demo' ? create<AuthState>()(initial) : create<AuthState>()(
  persist(
    initial,
    {
      name: "accessToken",
      storage: createJSONStorage(() => localStorage),
      partialize: (s) => ({ accessToken: s.accessToken }), 
      version: 1,
    }
  )
);
