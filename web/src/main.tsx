import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// Explicit `/index`: bare './app' is ambiguous on case-insensitive filesystems,
// where it can resolve to a sibling `App.tsx` instead of this directory — and
// then resolve differently again on Linux, where the production image is built.
import { App } from './app/index'
import { applyTheme, loadTheme } from './shared/lib/theme'
import './app/styles/index.css'

// Before the first render, so a saved theme never flashes the wrong colours.
applyTheme(loadTheme())

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
