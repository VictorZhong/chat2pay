export const APP_NAME = 'chat2pay';
export const USE_MOCK_API = (import.meta.env.VITE_USE_MOCK_API ?? 'true') !== 'false';
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '');
export const MOCK_API_LATENCY_MS = 220;
export const INTERACTION_DELAY_MS = 3000;
