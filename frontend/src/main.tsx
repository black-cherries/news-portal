import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
// Self-hosted Nastaliq for Urdu (no third-party font requests).
import '@fontsource/noto-nastaliq-urdu/arabic-400.css'
import './index.css'
import App from './App'
import { AuthProvider } from './auth/AuthProvider'
import { I18nProvider } from './i18n/I18nProvider'

// Production only: in development the service worker would serve stale code.
// Browsers allow service workers only on HTTPS and on localhost.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  })
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <I18nProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
      </I18nProvider>
    </BrowserRouter>
  </StrictMode>,
)
