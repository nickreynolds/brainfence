// Images imported by the renderer resolve to their URL (Vite asset handling).
declare module '*.png' {
  const url: string
  export default url
}

// The app's version name, from package.json (electron.vite.config.ts).
declare const __APP_VERSION__: string
