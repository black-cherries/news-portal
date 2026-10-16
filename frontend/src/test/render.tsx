import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { AuthProvider } from '../auth/AuthProvider'
import { I18nProvider } from '../i18n/I18nProvider'
import type { Lang } from '../i18n/messages'

/** Renders {@code element} at {@code path} with the app's providers. */
export function renderAt(element: ReactElement, { path = '/', route = '/', lang = 'en' as Lang } = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nProvider initial={lang}>
        <AuthProvider>
          <Routes>
            <Route path={route} element={element} />
          </Routes>
        </AuthProvider>
      </I18nProvider>
    </MemoryRouter>,
  )
}

/** Replaces fetch with canned JSON responses keyed by URL path (query ignored unless given). */
export function mockFetch(routes: Record<string, { status?: number; body?: unknown }>) {
  const calls: { url: string; init?: RequestInit }[] = []
  const fetchMock = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init })
    const route = routes[url] ?? routes[url.split('?')[0]]
    const status = route?.status ?? (route ? 200 : 404)
    const body = route?.body === undefined ? '' : JSON.stringify(route.body)
    return new Response(status === 204 ? null : body, { status, headers: { 'Content-Type': 'application/json' } })
  }
  globalThis.fetch = fetchMock as typeof fetch
  return calls
}
