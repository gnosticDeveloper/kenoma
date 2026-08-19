import { useEffect, useRef } from 'react'

/** Like useEffect, but runs immediately on mount and debounces subsequent dependency changes. */
export function useDebouncedEffect(callback: () => void, deps: unknown[], delayMs: number): void {
  const isFirstRun = useRef(true)

  useEffect(() => {
    if (isFirstRun.current) {
      isFirstRun.current = false
      callback()
      return
    }
    const id = setTimeout(callback, delayMs)
    return () => clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
}
