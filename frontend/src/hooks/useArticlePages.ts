import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { ArticlePage, ArticleSummary } from '../api/types'

interface Loaded {
  /** The request these results belong to. */
  path: string
  items: ArticleSummary[]
  nextCursor: string | null
  error: ApiError | Error | null
  loadingMore: boolean
}

/**
 * Loads a cursor-paginated article list. {@code path} must already contain
 * any filters; the hook adds the cursor for "load more". Pass null to load nothing.
 */
export function useArticlePages(path: string | null) {
  const [loaded, setLoaded] = useState<Loaded | null>(null)

  useEffect(() => {
    if (path === null) return
    let cancelled = false
    api
      .get<ArticlePage>(path)
      .then((page) => {
        if (!cancelled) setLoaded({ path, items: page.items, nextCursor: page.nextCursor, error: null, loadingMore: false })
      })
      .catch((err: Error) => {
        if (!cancelled) setLoaded({ path, items: [], nextCursor: null, error: err, loadingMore: false })
      })
    return () => {
      cancelled = true
    }
  }, [path])

  // Results from an older request are not shown while the new one loads.
  const current = loaded !== null && loaded.path === path ? loaded : null

  const loadMore = useCallback(async () => {
    if (!current?.nextCursor) return
    const { path: forPath, nextCursor } = current
    setLoaded((s) => (s ? { ...s, loadingMore: true } : s))
    const separator = forPath.includes('?') ? '&' : '?'
    try {
      const page = await api.get<ArticlePage>(`${forPath}${separator}cursor=${encodeURIComponent(nextCursor)}`)
      setLoaded((s) =>
        s && s.path === forPath
          ? { ...s, items: [...s.items, ...page.items], nextCursor: page.nextCursor, error: null, loadingMore: false }
          : s,
      )
    } catch (err) {
      setLoaded((s) => (s && s.path === forPath ? { ...s, error: err as Error, loadingMore: false } : s))
    }
  }, [current])

  return {
    items: current?.items ?? [],
    nextCursor: current?.nextCursor ?? null,
    loading: path !== null && (current === null || current.loadingMore),
    error: current?.error ?? null,
    loadMore,
  }
}

export function errorKey(err: unknown): 'common.tooMany' | 'common.error' {
  return err instanceof ApiError && err.status === 429 ? 'common.tooMany' : 'common.error'
}
