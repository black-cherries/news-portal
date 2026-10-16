import { useEffect, useId, useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { api, query } from '../api/client'
import type { Publisher } from '../api/types'
import { ArticleList } from '../components/ArticleList'
import { Icon } from '../components/Icon'
import { useArticlePages } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'
import { LANGUAGES, LANGUAGE_NAMES } from '../i18n/messages'

const ALL = 'all'

/**
 * Home: top stories, a topic (from the section bar) or search results
 * (FR-023, FR-028, FR-031). News starts in the interface language, English
 * by default; chips switch to another language or all of them. Filters live
 * in the URL.
 */
export function LatestPage() {
  const { t, lang, categoryName } = useI18n()
  const [params, setParams] = useSearchParams()
  const [publishers, setPublishers] = useState<Publisher[]>([])
  const ids = { source: useId(), from: useId(), to: useId() }

  useEffect(() => {
    api.get<Publisher[]>('/publishers').then(setPublishers, () => {})
  }, [])

  const langParam = params.get('lang')
  const newsLanguage = langParam ?? lang
  const q = params.get('q')
  const category = params.get('category')
  const path = useMemo(
    () =>
      '/articles' +
      query({
        q,
        lang: newsLanguage === ALL ? null : newsLanguage,
        category,
        source: params.get('source'),
        from: params.get('from'),
        to: params.get('to'),
      }),
    [params, q, category, newsLanguage],
  )
  const list = useArticlePages(path)

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setParams(next)
  }

  const chooseLanguage = (value: string) => update({ lang: value === lang ? null : value })

  const applyFilters = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    const value = (key: string) => String(form.get(key) ?? '').trim() || null
    update({ source: value('source'), from: value('from'), to: value('to') })
  }

  const filtersActive = Boolean(params.get('source') || params.get('from') || params.get('to'))
  const title = q ? t('feed.resultsFor', { q }) : category ? categoryName(category) : t('nav.topStories')

  return (
    <>
      <div className="page-head">
        <h1>{title}</h1>
        <div className="chip-row" role="group" aria-label={t('feed.newsIn')}>
          {LANGUAGES.map((l) => (
            <button
              key={l}
              type="button"
              className="chip"
              aria-pressed={newsLanguage === l}
              lang={l}
              onClick={() => chooseLanguage(l)}
            >
              {LANGUAGE_NAMES[l]}
            </button>
          ))}
          <button type="button" className="chip" aria-pressed={newsLanguage === ALL} onClick={() => chooseLanguage(ALL)}>
            {t('feed.allLanguages')}
          </button>
        </div>
      </div>

      <details className="filter-panel" open={filtersActive || undefined}>
        <summary>
          <Icon name="filter" size={16} /> {t('filters.more')}
        </summary>
        <form className="filters" onSubmit={applyFilters} key={params.toString()}>
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
      </details>

      <ArticleList
        items={list.items}
        loading={list.loading}
        error={list.error}
        hasMore={list.nextCursor !== null}
        onLoadMore={() => void list.loadMore()}
        featured={!q}
      />
    </>
  )
}
