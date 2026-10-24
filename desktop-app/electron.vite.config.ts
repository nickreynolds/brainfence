import { resolve } from 'node:path'
import { config as loadEnv } from 'dotenv'
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import react from '@vitejs/plugin-react'

// Same source of truth as the Android build (BuildConfig): the repo-root .env.
// Only public client values are baked in — never the Supabase secret key.
const env = { ...loadEnv({ path: resolve(__dirname, '../.env') }).parsed, ...process.env }

const shared = { '@shared': resolve(__dirname, 'src/shared') }

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    resolve: { alias: shared },
    define: {
      __SUPABASE_URL__: JSON.stringify(env.SUPABASE_URL ?? ''),
      __SUPABASE_PUBLISHABLE_KEY__: JSON.stringify(env.SUPABASE_PUBLISHABLE_KEY ?? ''),
      __POWERSYNC_URL__: JSON.stringify(env.POWERSYNC_URL ?? 'https://powersync.brainfence.online'),
    },
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    resolve: { alias: shared },
  },
  renderer: {
    resolve: { alias: shared },
    plugins: [react()],
  },
})
