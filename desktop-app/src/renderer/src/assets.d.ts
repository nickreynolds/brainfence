// Images imported by the renderer resolve to their URL (Vite asset handling).
declare module '*.png' {
  const url: string
  export default url
}
