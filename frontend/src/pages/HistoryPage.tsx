import { useCallback, useEffect, useState } from 'react'
import { api, query } from '../api/client'
import type { HistoryPage as HistoryData, Preferences } from '../api/types'
import { ArticleCard } from '../components/ArticleCard'
import { errorKey } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'

/** FR-047: review, remove, clear and pause reading/listening history. */
export function HistoryPage() {
  const { t, formatDateTime } = useI18n()
  const [page, setPage] = useState(0)
  const [data, setData] = useState<HistoryData | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    try {
      setData(await api.get<HistoryData>('/me/history' + query({ page })))
      setError(null)
    } catch (err) {
      setError(err)
    }
  }, [page])

  useEffect(() => {
    void load()
  }, [load])

  const setPaused = async (paused: boolean) => {
    try {
      const prefs = await api.get<Preferences>('/me/preferences')
      await api.put('/me/preferences', { ...prefs, historyPaused: paused })
      await load()
    } catch (err) {
      setError(err)
    }
  }

  const clearAll = async () => {
    if (!window.confirm(t('history.clearConfirm'))) return
    await api.delete('/me/history')
    setPage(0)
    await load()
  }

  return (
    <>
      <h1>{t('history.title')}</h1>
      <div className="actions">
        <button type="button" className="secondary" onClick={() => void setPaused(!data?.paused)}>
          {data?.paused ? t('history.resume') : t('history.pause')}
        </button>
        <button type="button" className="danger" onClick={() => void clearAll()} disabled={!data?.items.length}>
          {t('history.clear')}
        </button>
      </div>
      {data?.paused && <p className="notice">{t('history.paused')}</p>}
      {error ? (
        <p role="alert" className="error">
          {t(errorKey(error))}
        </p>
      ) : null}
      {data && data.items.length === 0 && <p>{t('common.empty')}</p>}
      <ul className="card-list">
        {data?.items.map((item) => (
          <li key={`${item.article.id}-${item.kind}`}>
            <ArticleCard
              article={item.article}
              extra={
                <p className="card-meta">
                  {item.kind === 'READ' ? t('history.read') : t('history.listened')}:{' '}
                  <time dateTime={item.lastAt}>{formatDateTime(item.lastAt)}</time>{' '}
                  <button
                    type="button"
                    className="secondary small"
                    onClick={async () => {
                      await api.delete(`/me/history/${item.article.id}${query({ kind: item.kind })}`)
                      await load()
                    }}
                  >
                    {t('history.remove')}
                    <span className="visually-hidden">: {item.article.title}</span>
                  </button>
                </p>
              }
            />
          </li>
        ))}
      </ul>
      <div className="actions">
        {page > 0 && (
          <button type="button" className="secondary" onClick={() => setPage(page - 1)}>
            ‹
          </button>
        )}
        {data?.hasMore && (
          <button type="button" className="secondary" onClick={() => setPage(page + 1)}>
            {t('common.loadMore')}
          </button>
        )}
      </div>
    </>
  )
}
