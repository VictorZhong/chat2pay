export const APP_NAME = 'chat2pay';

export const API_MODE_VALUES = ['mock', 'backend'] as const;

export type ApiMode = (typeof API_MODE_VALUES)[number];

function parseApiMode(value: string | undefined): ApiMode {
  return value?.trim().toLowerCase() === 'backend' ? 'backend' : 'mock';
}

function parseNonNegativeNumber(value: string | undefined, fallback: number) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

function trimTrailingSlash(value: string) {
  return value.endsWith('/') ? value.slice(0, -1) : value;
}

export const DEFAULT_API_MODE = parseApiMode(import.meta.env.VITE_API_MODE);
export const BACKEND_API_BASE_URL = trimTrailingSlash(import.meta.env.VITE_BACKEND_API_BASE_URL ?? '');
export const MOCK_API_LATENCY_MS = parseNonNegativeNumber(import.meta.env.VITE_MOCK_API_LATENCY_MS, 220);
export const MOCK_INTERACTION_DELAY_MS = parseNonNegativeNumber(
  import.meta.env.VITE_MOCK_INTERACTION_DELAY_MS,
  3000,
);

export function getInteractionDelayMs(apiMode: ApiMode) {
  return apiMode === 'mock' ? MOCK_INTERACTION_DELAY_MS : 0;
}
