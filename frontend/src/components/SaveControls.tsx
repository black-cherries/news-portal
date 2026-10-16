import { useEffect, useId, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { Collection } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { useI18n } from '../i18n/I18nProvider'

/** Bookmark toggle and "add to collection" for one article. Readers only. */
export function SaveControls({ articleId }: { articleId: number }) {
  const { t } = useI18n()
  const { isReader } = useAuth()
  const selectId = useId()
  const [bookmarked, setBookmarked] = useState(false)
  const [collections, setCollections] = useState<Collection[]>([])
  const [target, setTarget] = useState('')
  const [status, setStatus] = useState('')

  useEffect(() => {
    if (!isReader) return
    let cancelled = false
    void (async () => {
      try {
        const [cols, state] = await Promise.all([
          api.get<Collection[]>('/me/collections'),
          api.get<{ bookmarked: boolean }>(`/me/bookmarks/${articleId}`),
        ])
        if (cancelled) return
        setCollections(cols)
        setBookmarked(state.bookmarked)
      } catch {
        // Controls still work; state may be stale.
      }
    })()
    return () => {
      cancelled = true
    }
  }, [isReader, articleId])

  if (!isReader) {
    return (
      <p className="hint">
        <Link to="/sign-in">{t('save.signInToSave')}</Link>
      </p>
    )
  }

  const toggleBookmark = async () => {
    try {
      if (bookmarked) await api.delete(`/me/bookmarks/${articleId}`)
      else await api.put(`/me/bookmarks/${articleId}`)
      setBookmarked(!bookmarked)
    } catch {
      setStatus(t('common.error'))
    }
  }

  const addToCollection = async () => {
    const collection = collections.find((c) => String(c.id) === target)
    if (!collection) return
    try {
      await api.put(`/me/collections/${collection.id}/items/${articleId}`)
      setStatus(t('save.added', { name: collection.name }))
    } catch {
      setStatus(t('common.error'))
    }
  }

  return (
    <div className="save-controls">
      <button type="button" aria-pressed={bookmarked} onClick={() => void toggleBookmark()}>
        {bookmarked ? t('save.bookmarked') : t('save.bookmark')}
      </button>
      {collections.length > 0 && (
        <span className="inline-form">
          <label htmlFor={selectId} className="visually-hidden">
            {t('save.chooseCollection')}
          </label>
          <select id={selectId} value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">{t('save.chooseCollection')}</option>
            {collections.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          <button type="button" className="secondary" disabled={!target} onClick={() => void addToCollection()}>
            {t('save.addToCollection')}
          </button>
        </span>
      )}
      <p role="status" className="status">
        {status}
      </p>
    </div>
  )
}
