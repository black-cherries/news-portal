import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { ArticleDetail } from '../api/types'
import { ArticlePage } from '../pages/ArticlePage'
import { DownloadsPage } from '../pages/DownloadsPage'
import { mockFetch, renderAt } from '../test/render'
import { download, syncDownloads } from './downloads'
import { memoryStore, offlineStore, setOfflineStoreForTests, type OfflineArticle } from './store'

const LATER = new Date(Date.now() + 7 * 86_400_000).toISOString()
const EARLIER = new Date(Date.now() - 86_400_000).toISOString()

function article(id: number, overrides: Partial<ArticleDetail> = {}): ArticleDetail {
  return {
    id,
    title: `Story ${id}`,
    excerpt: 'Summary',
    author: null,
    publisher: { code: 'pub', name: 'Example News', homepageUrl: 'https://pub.example' },
    sourceUrl: `https://pub.example/${id}`,
    language: 'en',
    direction: 'ltr',
    publishedAt: null,
    publisherUpdatedAt: null,
    ingestedAt: '2026-10-01T08:00:00Z',
    lastSeenAt: '2026-10-01T09:00:00Z',
    categories: [],
    publisherCategories: [],
    fullTextStatus: 'AVAILABLE',
    fullTextHtml: '<p>Full body text</p>',
    fullTextNarrationPermitted: true,
    videos: [],
    ...overrides,
  }
}

function saved(id: number, overrides: Partial<OfflineArticle> = {}): OfflineArticle {
  return {
    id,
    article: article(id),
    level: 'FULL_TEXT',
    savedAt: '2026-10-02T10:00:00Z',
    checkedAt: '2026-10-02T10:00:00Z',
    validUntil: LATER,
    bytes: 2048,
    ...overrides,
  }
}

function networkDown() {
  globalThis.fetch = (async () => {
    throw new TypeError('Failed to fetch')
  }) as typeof fetch
}

beforeEach(() => {
  setOfflineStoreForTests(memoryStore())
})

describe('downloads', () => {
  it('keep full text only when the publisher allows it offline (FR-061)', async () => {
    mockFetch({
      '/api/v1/articles/offline-status?ids=1': { body: [{ id: 1, level: 'FULL_TEXT', validUntil: LATER }] },
      '/api/v1/articles/1': { body: article(1) },
      '/api/v1/articles/offline-status?ids=2': { body: [{ id: 2, level: 'METADATA', validUntil: LATER }] },
      '/api/v1/articles/2': { body: article(2) },
      '/api/v1/articles/offline-status?ids=3': { body: [{ id: 3, level: 'NONE', validUntil: null }] },
    })
    expect((await download(1)).article.fullTextHtml).toBe('<p>Full body text</p>')
    const metadataOnly = await download(2)
    expect(metadataOnly.level).toBe('METADATA')
    expect(metadataOnly.article.fullTextHtml).toBeNull()
    expect(metadataOnly.article.fullTextNarrationPermitted).toBe(false)
    await expect(download(3)).rejects.toThrow()
    expect((await offlineStore().list()).map((i) => i.id).sort()).toEqual([1, 2])
  })

  it('apply restrictions when the device is back online (FR-063)', async () => {
    const store = offlineStore()
    await store.put(saved(1))
    await store.put(saved(2))
    await store.put(saved(3, { validUntil: EARLIER }))
    mockFetch({
      '/api/v1/articles/offline-status?ids=1,2,3': {
        body: [
          { id: 1, level: 'NONE', validUntil: null },
          { id: 2, level: 'METADATA', validUntil: LATER },
          { id: 3, level: 'FULL_TEXT', validUntil: LATER },
        ],
      },
    })
    expect(await syncDownloads()).toEqual({ removed: 1, reduced: 1, checked: 2 })
    expect(await store.get(1)).toBeUndefined()
    expect((await store.get(2))?.article.fullTextHtml).toBeNull()
    expect((await store.get(3))?.validUntil).toBe(LATER)
  })

  it('are shown when the server cannot be reached, with a notice', async () => {
    await offlineStore().put(saved(7))
    networkDown()
    renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })
    expect(await screen.findByText(/You are offline. This is the copy saved on/)).toBeInTheDocument()
    expect(screen.getByText('Full body text')).toBeInTheDocument()
  })

  it('are hidden once their validity ends, until checked online', async () => {
    await offlineStore().put(saved(7, { validUntil: EARLIER }))
    networkDown()
    renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })
    expect(await screen.findByText(/must be checked against the publisher/)).toBeInTheDocument()
    expect(screen.queryByText('Full body text')).not.toBeInTheDocument()
  })

  it('are removed when the server says the article is gone', async () => {
    await offlineStore().put(saved(7))
    mockFetch({ '/api/v1/articles/7': { status: 404, body: {} }, '/api/v1/auth/me': { status: 401, body: {} } })
    renderAt(<ArticlePage />, { path: '/articles/7', route: '/articles/:id' })
    await screen.findByRole('heading', { level: 1 })
    expect(await offlineStore().get(7)).toBeUndefined()
  })

  it('can be listed and removed on the downloads page (FR-062)', async () => {
    await offlineStore().put(saved(1))
    await offlineStore().put(saved(2, { level: 'METADATA' }))
    mockFetch({ '/api/v1/auth/me': { status: 401, body: {} } })
    renderAt(<DownloadsPage />, { path: '/downloads', route: '/downloads' })

    expect(await screen.findByRole('link', { name: 'Story 1' })).toBeInTheDocument()
    expect(screen.getByText(/Headline and summary only/)).toBeInTheDocument()
    expect(screen.getByText(/may also delete downloads/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Remove all downloads' }))
    expect(await screen.findByText(/No downloads yet/)).toBeInTheDocument()
    expect(await offlineStore().list()).toEqual([])
  })
})
