import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { ArticleSummary } from '../api/types'
import { ArticleCard } from '../components/ArticleCard'
import { I18nProvider, useI18n } from '../i18n/I18nProvider'
import { mockFetch, renderAt } from '../test/render'
import { LatestPage } from './LatestPage'

function story(overrides: Partial<ArticleSummary> = {}): ArticleSummary {
  return {
    id: 1,
    title: 'Monsoon reaches the coast',
    excerpt: 'Rain arrives early this year.',
    publisher: { code: 'pub', name: 'Example News', homepageUrl: 'https://pub.example' },
    sourceUrl: 'https://pub.example/1',
    language: 'en',
    direction: 'ltr',
    publishedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
    publisherUpdatedAt: null,
    ingestedAt: new Date().toISOString(),
    categories: ['environment'],
    ...overrides,
  }
}

function ShowLang() {
  return <p>lang={useI18n().lang}</p>
}

describe('home and cards', () => {
  it('start in English whatever the browser language', () => {
    localStorage.clear()
    const original = Object.getOwnPropertyDescriptor(Navigator.prototype, 'language')
    Object.defineProperty(navigator, 'language', { value: 'hi-IN', configurable: true })
    try {
      render(
        <I18nProvider>
          <ShowLang />
        </I18nProvider>,
      )
      expect(screen.getByText('lang=en')).toBeInTheDocument()
    } finally {
      if (original) Object.defineProperty(navigator, 'language', original)
    }
  })

  it('show news in the interface language first, with a choice of others', async () => {
    const calls = mockFetch({
      '/api/v1/auth/me': { status: 401, body: {} },
      '/api/v1/publishers': { body: [] },
      '/api/v1/articles': { body: { items: [story()], nextCursor: null } },
    })
    renderAt(<LatestPage />)
    expect(await screen.findByRole('heading', { name: 'Top stories' })).toBeInTheDocument()
    expect(calls.some((c) => c.url === '/api/v1/articles?lang=en')).toBe(true)
    expect(screen.getByRole('button', { name: 'English' })).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(screen.getByRole('button', { name: 'All languages' }))
    expect(calls.some((c) => c.url === '/api/v1/articles?lang=all' || c.url === '/api/v1/articles')).toBe(true)
  })

  it('show the publisher image only when the API sends one, and label other languages', () => {
    const { container, rerender } = render(
      <MemoryRouter>
        <I18nProvider initial="en">
          <ArticleCard article={story({ imageUrl: 'https://cdn.example.org/a.jpg' })} />
        </I18nProvider>
      </MemoryRouter>,
    )
    const img = container.querySelector('img')
    expect(img?.getAttribute('src')).toBe('https://cdn.example.org/a.jpg')
    expect(img?.getAttribute('referrerpolicy')).toBe('no-referrer')
    expect(screen.getByText('Environment')).toBeInTheDocument()
    expect(screen.getByText(/^2 (hr\.?|hours) ago$/)).toBeInTheDocument()

    rerender(
      <MemoryRouter>
        <I18nProvider initial="en">
          <ArticleCard article={story({ language: 'ur', direction: 'rtl', title: 'خبر' })} />
        </I18nProvider>
      </MemoryRouter>,
    )
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('اردو')).toHaveAttribute('lang', 'ur')
  })
})
