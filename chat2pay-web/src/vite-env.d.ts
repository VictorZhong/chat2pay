/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_MODE?: string;
  readonly VITE_BACKEND_API_BASE_URL?: string;
  readonly VITE_BACKEND_PROXY_TARGET?: string;
  readonly VITE_MOCK_API_LATENCY_MS?: string;
  readonly VITE_MOCK_INTERACTION_DELAY_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
