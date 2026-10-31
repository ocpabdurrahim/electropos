import { useCallback, useEffect, useState } from 'react'

export type ThemeMode = 'system' | 'light' | 'dark'

export const THEMES: { value: ThemeMode; label: string }[] = [
  { value: 'system', label: 'System default' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
]

const THEME_KEY = 'electropos.theme'

function readTheme(): ThemeMode {
  try {
    const v = localStorage.getItem(THEME_KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

export function applyTheme(mode: ThemeMode = readTheme()) {
  if (mode === 'system') document.documentElement.removeAttribute('data-theme')
  else document.documentElement.setAttribute('data-theme', mode)
}

/** The theme is per browser, like the phone's themeMode (a local-only column). */
export function useTheme(): [ThemeMode, (mode: ThemeMode) => void] {
  const [mode, setMode] = useState<ThemeMode>(readTheme)
  useEffect(() => applyTheme(mode), [mode])
  const set = useCallback((m: ThemeMode) => {
    try {
      localStorage.setItem(THEME_KEY, m)
    } catch {
      // Storage blocked: the choice lasts for this visit.
    }
    setMode(m)
  }, [])
  return [mode, set]
}
