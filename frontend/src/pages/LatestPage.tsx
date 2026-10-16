import { useEffect, useId, useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { api, query } from '../api/client'
import type { Publisher } from '../api/types'
import { ArticleList } from '../components/ArticleList'
import { useArticlePages } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'
import { LANGUAGES, LANGUAGE_NAMES } from '../i18n/messages'

/** Latest feed with search and filters (FR-023, FR-028, FR-031). Filters live in the URL. */
export function LatestPage() {
  const { t, categoryName } = useI18n()
  const [params, setParams] = useSearchParams()
  const [categories, setCategories] = useState<string[]>([])
  const [publishers, setPublishers] = useState<Publisher[]>([])
  const ids = { q: useId(), category: useId(), source: useId(), from: useId(), to: useId() }

  useEffect(() => {
    api.get<{ code: string }[]>('/categories').then((c) => setCategories(c.map((x) => x.code)), () => {})
    api.get<Publisher[]>('/publishers').then(setPublishers, () => {})
  }, [])

  const languages = params.get('lang')?.split(',').filter(Boolean) ?? []
  const path = useMemo(
    () =>
      '/articles' +
      query({
        q: params.get('q'),
        lang: params.get('lang'),
        category: params.get('category'),
        source: params.get('source'),
        from: params.get('from'),
        to: params.get('to'),
      }),
    [params],
  )
  const list = useArticlePages(path)

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    const next = new URLSearchParams()
    for (const key of ['q', 'category', 'source', 'from', 'to']) {
      const value = String(form.get(key) ?? '').trim()
      if (value) next.set(key, value)
    }
    const langs = form.getAll('lang').map(String)
    if (langs.length) next.set('lang', langs.join(','))
    setParams(next)
  }

  return (
    <>
      <h1>{t('feed.latestTitle')}</h1>
      <form className="filters" onSubmit={submit} role="search" key={params.toString()}>
        <div className="field grow">
          <label htmlFor={ids.q}>{t('filters.search')}</label>
          <input
            id={ids.q}
            name="q"
            type="search"
            maxLength={200}
            defaultValue={params.get('q') ?? ''}
            placeholder={t('filters.searchPlaceholder')}
          />
        </div>
        <fieldset className="field">
          <legend>{t('filters.languages')}</legend>
          {LANGUAGES.map((l) => (
            <label key={l} className="check">
              <input type="checkbox" name="lang" value={l} defaultChecked={languages.includes(l)} />
              <span lang={l}>{LANGUAGE_NAMES[l]}</span>
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor={ids.category}>{t('filters.category')}</label>
          <select id={ids.category} name="category" defaultValue={params.get('category') ?? ''}>
            <option value="">{t('filters.any')}</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {categoryName(c)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={ids.source}>{t('filters.publisher')}</label>
          <select id={ids.source} name="source" defaultValue={params.get('source') ?? ''}>
            <option value="">{t('filters.any')}</option>
            {publishers.map((p) => (
              <option key={p.code} value={p.code}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={ids.from}>{t('filters.from')}</label>
          <input id={ids.from} name="from" type="date" defaultValue={params.get('from') ?? ''} />
        </div>
        <div className="field">
          <label htmlFor={ids.to}>{t('filters.to')}</label>
          <input id={ids.to} name="to" type="date" defaultValue={params.get('to') ?? ''} />
        </div>
        <div className="actions">
          <button type="submit">{t('common.apply')}</button>
          {params.toString() && (
            <button type="button" className="secondary" onClick={() => setParams(new URLSearchParams())}>
              {t('filters.reset')}
            </button>
          )}
        </div>
      </form>
      <ArticleList
        items={list.items}
        loading={list.loading}
        error={list.error}
        hasMore={list.nextCursor !== null}
        onLoadMore={() => void list.loadMore()}
      />
    </>
  )
}
