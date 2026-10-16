import { useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, api, query } from '../api/client'
import type { Collection, SavedPage as SavedPageData } from '../api/types'
import { ArticleCard } from '../components/ArticleCard'
import { errorKey } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'

/** A page of saved articles (bookmarks or one collection) with search and remove. */
function SavedList({ basePath, removePath }: { basePath: string; removePath: (articleId: number) => string }) {
  const { t } = useI18n()
  const searchId = useId()
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<SavedPageData | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    try {
      setData(await api.get<SavedPageData>(basePath + query({ q, page })))
      setError(null)
    } catch (err) {
      setError(err)
    }
  }, [basePath, q, page])

  useEffect(() => {
    void load()
  }, [load])

  const remove = async (articleId: number) => {
    try {
      await api.delete(removePath(articleId))
      await load()
    } catch (err) {
      setError(err)
    }
  }

  return (
    <section>
      <form
        role="search"
        className="inline-form"
        onSubmit={(e) => {
          e.preventDefault()
          setPage(0)
          setQ(String(new FormData(e.currentTarget).get('q') ?? '').trim())
        }}
      >
        <label htmlFor={searchId}>{t('saved.searchSaved')}</label>
        <input id={searchId} name="q" type="search" maxLength={200} defaultValue={q} />
        <button type="submit" className="secondary">
          {t('filters.search')}
        </button>
      </form>
      {error ? (
        <p role="alert" className="error">
          {t(errorKey(error))}
        </p>
      ) : null}
      {data && data.hiddenCount > 0 && <p className="notice">{t('saved.hidden', { count: data.hiddenCount })}</p>}
      {data && data.items.length === 0 && <p>{t('common.empty')}</p>}
      <ul className="card-list">
        {data?.items.map((item) => (
          <li key={item.article.id}>
            <ArticleCard
              article={item.article}
              extra={
                <button type="button" className="secondary small" onClick={() => void remove(item.article.id)}>
                  {t('saved.remove')}
                  <span className="visually-hidden">: {item.article.title}</span>
                </button>
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
    </section>
  )
}

export function SavedPage() {
  const { t } = useI18n()
  const nameId = useId()
  const [collections, setCollections] = useState<Collection[]>([])
  const [message, setMessage] = useState<string | null>(null)

  const load = useCallback(() => {
    api.get<Collection[]>('/me/collections').then(setCollections, () => {})
  }, [])
  useEffect(load, [load])

  const create = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    const name = String(new FormData(form).get('name') ?? '').trim()
    if (!name) return
    try {
      await api.post('/me/collections', { name })
      form.reset()
      setMessage(null)
      load()
    } catch (err) {
      setMessage(err instanceof ApiError && err.status === 409 ? t('saved.nameTaken') : t(errorKey(err)))
    }
  }

  return (
    <>
      <h1>{t('saved.title')}</h1>
      <section aria-labelledby="collections-heading">
        <h2 id="collections-heading">{t('saved.collections')}</h2>
        <ul className="plain-list">
          {collections.map((c) => (
            <li key={c.id}>
              <Link to={`/saved/collections/${c.id}`}>{c.name}</Link>{' '}
              <span className="hint">{t('saved.items', { count: c.itemCount })}</span>
            </li>
          ))}
        </ul>
        <form className="inline-form" onSubmit={create}>
          <label htmlFor={nameId}>{t('saved.newCollection')}</label>
          <input id={nameId} name="name" maxLength={100} required />
          <button type="submit">{t('saved.create')}</button>
        </form>
        {message && (
          <p role="alert" className="error">
            {message}
          </p>
        )}
      </section>
      <section aria-labelledby="bookmarks-heading">
        <h2 id="bookmarks-heading">{t('saved.bookmarks')}</h2>
        <SavedList basePath="/me/bookmarks" removePath={(id) => `/me/bookmarks/${id}`} />
      </section>
    </>
  )
}

export function CollectionPage() {
  const { id } = useParams()
  const { t } = useI18n()
  const renameId = useId()
  const [collection, setCollection] = useState<Collection | null>(null)
  const [gone, setGone] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    api.get<Collection[]>('/me/collections').then(
      (all) => {
        const found = all.find((c) => String(c.id) === id)
        if (found) setCollection(found)
        else setGone(true)
      },
      () => setGone(true),
    )
  }, [id])

  if (gone) {
    return (
      <>
        <h1>{t('common.notFound')}</h1>
        <Link to="/saved">{t('saved.title')}</Link>
      </>
    )
  }
  if (!collection) return <p role="status">{t('common.loading')}</p>

  const rename = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const name = String(new FormData(e.currentTarget).get('name') ?? '').trim()
    try {
      setCollection(await api.patch<Collection>(`/me/collections/${collection.id}`, { name }))
      setMessage(t('settings.saved'))
    } catch (err) {
      setMessage(err instanceof ApiError && err.status === 409 ? t('saved.nameTaken') : t(errorKey(err)))
    }
  }

  const remove = async () => {
    if (!window.confirm(t('saved.deleteCollectionConfirm'))) return
    await api.delete(`/me/collections/${collection.id}`)
    setGone(true)
  }

  return (
    <>
      <p>
        <Link to="/saved">{t('saved.title')}</Link>
      </p>
      <h1>{collection.name}</h1>
      <form className="inline-form" onSubmit={rename}>
        <label htmlFor={renameId}>{t('common.rename')}</label>
        <input id={renameId} name="name" maxLength={100} required defaultValue={collection.name} />
        <button type="submit" className="secondary">
          {t('common.save')}
        </button>
        <button type="button" className="danger" onClick={() => void remove()}>
          {t('common.delete')}
        </button>
      </form>
      {message && <p role="status">{message}</p>}
      <SavedList
        basePath={`/me/collections/${collection.id}/items`}
        removePath={(articleId) => `/me/collections/${collection.id}/items/${articleId}`}
      />
    </>
  )
}
