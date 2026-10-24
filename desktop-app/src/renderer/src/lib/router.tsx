import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react'

/**
 * A tiny stack router. Routes are strings like "home", "task/<id>",
 * "editor/<id>", "blocking/<id>" — the same shapes as the Android NavGraph.
 */
interface RouterValue {
  route: string
  navigate: (route: string, opts?: { replace?: boolean; reset?: boolean }) => void
  back: () => void
  canGoBack: boolean
}

const RouterContext = createContext<RouterValue | null>(null)

export function RouterProvider({ initial, children }: { initial: string; children: ReactNode }) {
  const [stack, setStack] = useState<string[]>([initial])

  const navigate = useCallback((route: string, opts?: { replace?: boolean; reset?: boolean }) => {
    setStack((s) => {
      if (opts?.reset) return [route]
      if (opts?.replace) return [...s.slice(0, -1), route]
      if (s[s.length - 1] === route) return s
      return [...s, route]
    })
  }, [])
  const back = useCallback(() => setStack((s) => (s.length > 1 ? s.slice(0, -1) : s)), [])

  // Main process asks to show a route (tray menu, notifications, overlay).
  useEffect(() => window.api.window.onNavigate((route) => navigate(route, { reset: !route.includes('/') })), [navigate])

  // Mouse back button / Alt+Left.
  useEffect(() => {
    const onMouse = (e: MouseEvent) => e.button === 3 && back()
    const onKey = (e: KeyboardEvent) => e.altKey && e.key === 'ArrowLeft' && back()
    window.addEventListener('mouseup', onMouse)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mouseup', onMouse)
      window.removeEventListener('keydown', onKey)
    }
  }, [back])

  const value = useMemo(
    () => ({ route: stack[stack.length - 1], navigate, back, canGoBack: stack.length > 1 }),
    [stack, navigate, back],
  )
  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>
}

export function useRouter(): RouterValue {
  const v = useContext(RouterContext)
  if (!v) throw new Error('useRouter outside RouterProvider')
  return v
}

/** "task/abc" → ["task", "abc"] */
export function routeParts(route: string): string[] {
  return route.split('/')
}
