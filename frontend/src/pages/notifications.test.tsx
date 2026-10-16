import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch, renderAt } from '../test/render'
import { SettingsPage } from './SettingsPage'
import { UnsubscribePage } from './UnsubscribePage'

const reader = { id: 2, email: 'r@example.org', accountType: 'READER', roles: [] }
const prefs = { uiLanguage: 'en', newsLanguages: [], topics: [], digestLanguage: null, historyPaused: false }
const off = { digest: 'OFF', breakingAlerts: false, languages: [], topics: [], nextDigestAt: null }
const TOKEN = 'a'.repeat(43)

describe('email notifications', () => {
  it('are off by default and saved with the chosen frequency, alerts and languages', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const calls = mockFetch({
      '/api/v1/auth/me': { body: reader },
      '/api/v1/me/preferences': { body: prefs },
      '/api/v1/categories': { body: [{ code: 'sports' }] },
      '/api/v1/me/notifications': { body: off },
    })
    renderAt(<SettingsPage />, { path: '/settings', route: '/settings' })

    const digest = (await screen.findByLabelText('Email digest')) as HTMLSelectElement
    expect(digest.value).toBe('OFF')
    const alerts = screen.getByLabelText('Breaking-news alerts for major stories') as HTMLInputElement
    expect(alerts.checked).toBe(false)

    await userEvent.selectOptions(digest, 'DAILY')
    await userEvent.click(alerts)
    const languages = screen.getByRole('group', { name: 'Languages for notifications' })
    await userEvent.click(languages.querySelector('input[type=checkbox]') as HTMLInputElement)
    const saveButtons = screen.getAllByRole('button', { name: 'Save' })
    await userEvent.click(saveButtons[1])

    const put = calls.find((c) => c.url === '/api/v1/me/notifications' && c.init?.method === 'PUT')
    expect(JSON.parse(String(put?.init?.body))).toMatchObject({ digest: 'DAILY', breakingAlerts: true, languages: ['en'] })
  })

  it('unsubscribe asks before changing anything, then sends the token and kind from the link', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    window.history.replaceState(null, '', `/unsubscribe#token=${TOKEN}&kind=DIGEST`)
    const calls = mockFetch({
      '/api/v1/auth/me': { status: 401 },
      '/api/v1/notifications/unsubscribe': { body: { unsubscribed: true } },
    })
    renderAt(<UnsubscribePage />, { path: '/unsubscribe', route: '/unsubscribe' })

    expect(await screen.findByRole('button', { name: 'Stop email digests' })).toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('/unsubscribe'))).toBe(false)
    // The token is removed from the address bar.
    expect(window.location.hash).toBe('')

    await userEvent.click(screen.getByRole('button', { name: 'Stop email digests' }))
    expect(await screen.findByText(/You will not get these emails any more/)).toBeInTheDocument()
    const post = calls.find((c) => c.url === '/api/v1/notifications/unsubscribe')
    expect(JSON.parse(String(post?.init?.body))).toEqual({ token: TOKEN, kind: 'DIGEST' })
  })

  it('unsubscribe explains when the link is missing or not valid', async () => {
    window.history.replaceState(null, '', '/unsubscribe')
    mockFetch({ '/api/v1/auth/me': { status: 401 } })
    renderAt(<UnsubscribePage />, { path: '/unsubscribe', route: '/unsubscribe' })
    expect(await screen.findByText(/This unsubscribe link is not valid/)).toBeInTheDocument()
  })
})
