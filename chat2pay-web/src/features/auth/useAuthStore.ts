import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { CurrentUserContext } from '@/shared/api/contracts';

interface AuthState {
  currentUser: CurrentUserContext | null;
  setCurrentUser: (user: CurrentUserContext) => void;
  clearCurrentUser: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      currentUser: null,
      setCurrentUser: (user) => set({ currentUser: user }),
      clearCurrentUser: () => set({ currentUser: null }),
    }),
    {
      name: 'chat2pay-auth',
    },
  ),
);
