/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_MESHDROP_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
