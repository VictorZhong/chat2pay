import { create } from 'zustand';

interface SidebarState {
  collapsed: boolean;
  setCollapsed: (value: boolean) => void;
}

export const useSidebarStore = create<SidebarState>((set) => ({
  collapsed: false,
  setCollapsed: (value) => set({ collapsed: value }),
}));
