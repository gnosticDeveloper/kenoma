import { useEffect, useState } from 'react'
import { applyTheme, getStoredMode, storeMode, type ThemeMode } from '../theme'

export function useTheme() {
  const [mode, setModeState] = useState<ThemeMode>(getStoredMode)

  useEffect(() => {
    applyTheme(mode)
    storeMode(mode)
    if (mode !== 'auto') return
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => applyTheme(mode)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [mode])

  function cycle() {
    setModeState(m => (m === 'auto' ? 'dark' : m === 'dark' ? 'light' : 'auto'))
  }

  return { mode, setMode: setModeState, cycle }
}
