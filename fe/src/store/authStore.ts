import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

type AuthState = {
  accessToken: string | null;
  setAccessToken: (t: string | null) => void;
  clear: () => void;
};

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      setAccessToken: (t) => set({ accessToken: t }),
      clear: () => set({ accessToken: null }),
    }),
    {
      name: "accessToken",
      storage: createJSONStorage(() => localStorage),
      partialize: (s) => ({ accessToken: s.accessToken }), 
      version: 1,
    }
  )
);
