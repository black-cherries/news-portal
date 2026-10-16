import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ArticleDetail, Translation } from '../api/types'
import { ArticlePage } from '../pages/ArticlePage'
import { mockFetch, renderAt } from '../test/render'

const reader = { id: 2, email: 'r@example.org', accountType: 'READER', roles: [] }

const article: ArticleDetail = {
  id: 7,
  title: 'Water supply restored',
  excerpt: 'Repairs finished overnight.',
  author: null,
  publisher: { code: 'pub', name: 'Example News', homepageUrl: 'https://pub.example' },
  sourceUrl: 'https://pub.example/7',
  language: 'en',
  direction: 'ltr',
  publishedAt: '2026-10-01T08:00:00Z',
  publisherUpdatedAt: null,
  ingestedAt: '2026-10-01T08:00:00Z',
  lastSeenAt: '2026-10-01T09:00:00Z',
  categories: [],
  publisherCategories: [],
  fullTextStatus: 'AVAILABLE',
  fullTextHtml: '<p>Crews worked all night.</p>',
  fullTextNarrationPermitted: false,
  videos: [],
  translationAvailable: true,
}

const urdu: Translation = {
  articleId: 7,
  sourceLanguage: 'en',
  language: 'ur',
  direction: 'rtl',
  title: 'پانی کی فراہمی بحال',
  excerpt: 'مرمت رات بھر میں مکمل ہوئی۔',
  bodyHtml: '<p>عملے نے رات بھر کام کیا۔</p><img src=x onerror=alert(1)>',
  machineTranslated: true,
  model: 'claude-sonnet-5-5',
  translatedAt: '2026-10-02T10:00:00Z',
}

describe('translation', () => {
  it('a reader translates on demand; the result replaces the original, labelled, in its own direction', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const calls = mockFetch({
      '/api/v1/auth/me': { body: reader },
      '/api/v1/articles/7': { body: article },
      '/api/v1/me/history': { body: { recorded: true } },
      '/api/v1/me/bookmarks/7': { body: { bookmarked: false } },
      // GET finds no cached copy; POST makes one (same path, see the method check below).
      '/api/v1/articles/7/translations/ur': { status: 404, body: {} },
    })
    renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })

    const select = (await screen.findByLabelText('Read in')) as HTMLSelectElement
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['hi', 'ur', 'ar'])
    await userEvent.selectOptions(select, 'ur')

    // Answer the POST with the translation.
    const original = globalThis.fetch
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/v1/articles/7/translations/ur' && init?.method === 'POST') {
        calls.push({ url: String(input), init })
        return new Response(JSON.stringify(urdu), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return original(input, init)
    }) as typeof fetch

    await userEvent.click(screen.getByRole('button', { name: 'Translate' }))
    const heading = await screen.findByRole('heading', { level: 1, name: 'پانی کی فراہمی بحال' })
    expect(heading).toHaveAttribute('lang', 'ur')
    expect(heading).toHaveAttribute('dir', 'rtl')
    expect(screen.getByText(/Machine translation from English/)).toBeInTheDocument()
    expect(document.querySelector('.article-body img')?.getAttribute('onerror')).toBeFalsy()
    expect(calls.filter((c) => c.url.endsWith('/translations/ur')).map((c) => c.init?.method ?? 'GET')).toEqual([
      'GET',
      'POST',
    ])

    await userEvent.click(screen.getByRole('button', { name: 'Show original' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Water supply restored' })).toBeInTheDocument()
  })

  it('guests are asked to sign in when there is no translation yet', async () => {
    mockFetch({
      '/api/v1/auth/me': { status: 401, body: {} },
      '/api/v1/articles/7': { body: article },
      '/api/v1/articles/7/translations/hi': { status: 404, body: {} },
    })
    renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })
    await userEvent.click(await screen.findByRole('button', { name: 'Translate' }))
    expect(await screen.findByRole('link', { name: 'Sign in to translate this article.' })).toBeInTheDocument()
  })

  it('is hidden when the publisher does not allow translation', async () => {
    mockFetch({
      '/api/v1/auth/me': { status: 401, body: {} },
      '/api/v1/articles/7': { body: { ...article, translationAvailable: false } },
    })
    renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('button', { name: 'Translate' })).not.toBeInTheDocument()
  })
})
