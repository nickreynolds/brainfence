// Build-time configuration, baked in from the repo-root .env by electron.vite.config.ts.

declare const __SUPABASE_URL__: string
declare const __SUPABASE_PUBLISHABLE_KEY__: string
declare const __POWERSYNC_URL__: string

export const SUPABASE_URL = __SUPABASE_URL__
export const SUPABASE_PUBLISHABLE_KEY = __SUPABASE_PUBLISHABLE_KEY__
export const POWERSYNC_URL = __POWERSYNC_URL__

export const APP_USER_MODEL_ID = 'dev.brainfence.desktop'
export const PROTOCOL = 'brainfence'
