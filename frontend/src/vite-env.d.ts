/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly BASE_DOMAIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
