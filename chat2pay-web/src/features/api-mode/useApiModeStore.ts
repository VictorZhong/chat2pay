import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { DEFAULT_API_MODE, type ApiMode } from '@/shared/config/env';

interface ApiModeState {
  apiMode: ApiMode;
  setApiMode: (apiMode: ApiMode) => void;
}

export const useApiModeStore = create<ApiModeState>()(
  persist(
    (set) => ({
      apiMode: DEFAULT_API_MODE,
      setApiMode: (apiMode) => set({ apiMode }),
    }),
    {
      name: 'chat2pay-api-mode',
    },
  ),
);

export function getApiMode() {
  return useApiModeStore.getState().apiMode;
}
