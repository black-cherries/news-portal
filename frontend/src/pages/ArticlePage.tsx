import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, api } from '../api/client'
import type { ArticleDetail } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { ListenButton } from '../components/ListenButton'
import { SaveControls } from '../components/SaveControls'
import { Timestamps } from '../components/Timestamps'
import { useI18n } from '../i18n/I18nProvider'
import { plainText, sanitize } from '../lib/html'

export function ArticlePage() {
  const { id } = useParams()
  const { t, categoryName } = useI18n()
  const { isReader } = useAuth()
  const [result, setResult] = useState<{ id: string; article?: ArticleDetail; error?: 'notFound' | 'error' } | null>(null)

  useEffect(() => {
    if (!id) return
    let cancelled = false
    api
      .get<ArticleDetail>(`/articles/${encodeURIComponent(id)}`)
      .then((a) => {
        if (!cancelled) setResult({ id, article: a })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const notFound = err instanceof ApiError && (err.status === 404 || err.status === 400)
        setResult({ id, error: notFound ? 'notFound' : 'error' })
      })
    return () => {
      cancelled = true
    }
  }, [id])

  // Ignore a result that belongs to the previous article while the next one loads.
  const current = result && result.id === id ? result : null
  const article = current?.article ?? null
  const error = current?.error ?? null

  // FR-047: record reading for signed-in readers (the server ignores it while history is paused).
  useEffect(() => {
    if (article && isReader) {
      api.post('/me/history', { articleId: article.id, kind: 'READ' }).catch(() => {})
    }
  }, [article, isReader])

  const bodyHtml = useMemo(
    () => (article?.fullTextStatus === 'AVAILABLE' && article.fullTextHtml ? sanitize(article.fullTextHtml) : null),
    [article],
  )

  if (error === 'notFound') {
    return (
      <>
        <h1>{t('article.notFound')}</h1>
        <p>
          <Link to="/">{t('common.backHome')}</Link>
        </p>
      </>
    )
  }
  if (error) return <p role="alert" className="error">{t('common.error')}</p>
  if (!article) return <p role="status">{t('common.loading')}</p>

  // FR-059: full text is read aloud only when narration is permitted; otherwise headline and summary.
  const narrateFull = article.fullTextNarrationPermitted && article.fullTextHtml !== null
  const spoken = narrateFull
    ? `${article.title}. ${plainText(article.fullTextHtml ?? '')}`
    : [article.title, article.excerpt].filter(Boolean).join('. ')
  const publisher = article.publisher.name

  return (
    <article className="article">
      <header>
        <h1 lang={article.language} dir={article.direction}>
          {article.title}
        </h1>
        <p className="attribution">
          {t('article.attribution', { publisher })}{' '}
          <a href={article.sourceUrl} target="_blank" rel="noopener noreferrer">
            {t('article.readAtSource', { publisher })}
            <span className="visually-hidden"> {t('common.opensNewTab')}</span>
          </a>
        </p>
        {article.author && (
          <p lang={article.language} dir={article.direction}>
            {t('article.by', { author: article.author })}
          </p>
        )}
        <Timestamps
          publishedAt={article.publishedAt}
          publisherUpdatedAt={article.publisherUpdatedAt}
          ingestedAt={article.ingestedAt}
        />
        {article.categories.length > 0 && (
          <p>
            {t('article.categories')}: {article.categories.map(categoryName).join(', ')}
          </p>
        )}
      </header>

      <div className="article-tools">
        <ListenButton
          text={spoken}
          language={article.language}
          summaryOnly={!narrateFull}
          onStart={() => {
            if (isReader) api.post('/me/history', { articleId: article.id, kind: 'LISTEN' }).catch(() => {})
          }}
        />
        <SaveControls articleId={article.id} />
      </div>

      {article.excerpt && !bodyHtml && (
        <p className="lead" lang={article.language} dir={article.direction}>
          {article.excerpt}
        </p>
      )}

      {bodyHtml ? (
        <div
          className="article-body"
          lang={article.language}
          dir={article.direction}
          dangerouslySetInnerHTML={{ __html: bodyHtml }}
        />
      ) : (
        <p className="notice">
          {article.fullTextStatus === 'NOT_PERMITTED' ? t('article.fullTextNotPermitted') : t('article.fullTextNotProvided')}{' '}
          <a href={article.sourceUrl} target="_blank" rel="noopener noreferrer">
            {t('article.readAtSource', { publisher })}
            <span className="visually-hidden"> {t('common.opensNewTab')}</span>
          </a>
        </p>
      )}
    </article>
  )
}
