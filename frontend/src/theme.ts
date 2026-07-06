export type ThemeMode = 'dark' | 'light' | 'auto'
export type ResolvedTheme = 'dark' | 'light'

const MODE_KEY = 'kenoma.theme.mode'

export function getStoredMode(): ThemeMode {
  const stored = localStorage.getItem(MODE_KEY)
  return stored === 'dark' || stored === 'light' || stored === 'auto' ? stored : 'auto'
}

export function storeMode(mode: ThemeMode): void {
  localStorage.setItem(MODE_KEY, mode)
}

export function resolveTheme(mode: ThemeMode): ResolvedTheme {
  if (mode === 'auto') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return mode
}

export function applyTheme(mode: ThemeMode): void {
  document.documentElement.setAttribute('data-theme', resolveTheme(mode))
}
