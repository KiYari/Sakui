/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** `same-origin` (default), `relay` or `direct` — see shared/lib/server-target. */
  readonly VITE_EECK_SERVER_MODE?: string
}
