import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Vitest globals are off, so testing-library's auto-cleanup never registers -- without this, DOM leaks between tests.
afterEach(() => {
  cleanup()
})

// jsdom has no matchMedia; UiStore reads it for the OS theme default, and RootStore builds every
// store eagerly, so nearly every test would throw before it even ran without this.
if (typeof window.matchMedia !== 'function') {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList
}
