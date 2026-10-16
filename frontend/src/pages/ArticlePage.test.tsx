import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ArticleDetail } from '../api/types'
import { mockFetch, renderAt } from '../test/render'
import { ArticlePage } from './ArticlePage'

function article(overrides: Partial<ArticleDetail> = {}): ArticleDetail {
  return {
    id: 7,
    title: 'پاکستان کی تازہ خبریں',
    excerpt: 'خلاصہ',
    author: null,
    publisher: { code: 'pub', name: 'Example News', homepageUrl: 'https://pub.example' },
    sourceUrl: 'https://pub.example/story-7',
    language: 'ur',
    direction: 'rtl',
    publishedAt: null,
    publisherUpdatedAt: null,
    ingestedAt: '2026-10-01T08:00:00Z',
    lastSeenAt: '2026-10-01T09:00:00Z',
    categories: ['politics'],
    publisherCategories: ['Politics'],
    fullTextStatus: 'NOT_PERMITTED',
    fullTextHtml: null,
    fullTextNarrationPermitted: false,
    ...overrides,
  }
}

function show(a: ArticleDetail) {
  mockFetch({ '/api/v1/articles/7': { body: a }, '/api/v1/auth/me': { status: 401, body: {} } })
  return renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })
}

describe('ArticlePage', () => {
  it('shows attribution, the source link and marks unknown times as unknown', async () => {
    show(article())
    const heading = await screen.findByRole('heading', { level: 1 })
    expect(heading).toHaveAttribute('lang', 'ur')
    expect(heading).toHaveAttribute('dir', 'rtl')
    expect(screen.getByText(/Reporting by Example News/)).toBeInTheDocument()
    const links = screen.getAllByRole('link', { name: /Read at Example News/ })
    expect(links[0]).toHaveAttribute('href', 'https://pub.example/story-7')
    expect(links[0]).toHaveAttribute('rel', 'noopener noreferrer')
    // Published and updated are unknown; ingestion time is known.
    expect(screen.getAllByText('Not provided by the publisher')).toHaveLength(2)
  })

  it('explains when the publisher has not permitted full text', async () => {
    show(article())
    expect(await screen.findByText(/has not allowed the full article/)).toBeInTheDocument()
  })

  it('renders permitted full text after sanitizing it again', async () => {
    show(article({ fullTextStatus: 'AVAILABLE', fullTextHtml: '<p>Body text</p><script>alert(1)</script>' }))
    const body = await screen.findByText('Body text')
    expect(body.closest('.article-body')?.innerHTML).not.toContain('script')
    expect(screen.queryByText(/has not allowed/)).not.toBeInTheDocument()
  })

  it('shows a not-available message for hidden or missing articles', async () => {
    mockFetch({ '/api/v1/articles/7': { status: 404, body: {} }, '/api/v1/auth/me': { status: 401, body: {} } })
    renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })
    expect(await screen.findByText(/This article is not available/)).toBeInTheDocument()
  })

  it('tells the reader when listening is not possible in this browser', async () => {
    show(article())
    expect(await screen.findByText(/Listening is not supported/)).toBeInTheDocument()
  })
})
