import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, api } from '../api/client'
import type { ArticleDetail, Translation } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { DownloadButton } from '../components/DownloadButton'
import { ListenButton } from '../components/ListenButton'
import { TranslateBar } from '../components/TranslateBar'
import { VideoList } from '../components/VideoList'
import { isUsable, removeDownload } from '../offline/downloads'
import { offlineStore, type OfflineArticle } from '../offline/store'
import { ReportForm } from '../components/ReportForm'
import { SaveControls } from '../components/SaveControls'
import { Timestamps } from '../components/Timestamps'
import { useI18n } from '../i18n/I18nProvider'
import { plainText, sanitize } from '../lib/html'

export function ArticlePage() {
  const { id } = useParams()
  const { t, categoryName, formatDateTime } = useI18n()
  const { isReader } = useAuth()
  const [result, setResult] = useState<{
    id: string
    article?: ArticleDetail
    offline?: OfflineArticle
    error?: 'notFound' | 'error' | 'expired'
  } | null>(null)

  useEffect(() => {
    if (!id) return
    let cancelled = false
    api
      .get<ArticleDetail>(`/articles/${encodeURIComponent(id)}`)
      .then((a) => {
        if (!cancelled) setResult({ id, article: a })
      })
      .catch(async (err: unknown) => {
        if (cancelled) return
        const notFound = err instanceof ApiError && (err.status === 404 || err.status === 400)
        if (notFound) {
          // The server no longer shows it, so a download must go too (FR-063).
          await removeDownload(Number(id)).catch(() => {})
          if (!cancelled) setResult({ id, error: 'notFound' })
          return
        }
        // No answer from the server: fall back to a download if there is one.
        const copy = err instanceof ApiError ? undefined : await offlineStore().get(Number(id)).catch(() => undefined)
        if (cancelled) return
        if (!copy) setResult({ id, error: 'error' })
        else if (!isUsable(copy)) setResult({ id, error: 'expired' })
        else setResult({ id, article: copy.article, offline: copy })
      })
    return () => {
      cancelled = true
    }
  }, [id])

  // Ignore a result that belongs to the previous article while the next one loads.
  const current = result && result.id === id ? result : null
  const article = current?.article ?? null
  const offline = current?.offline ?? null
  const error = current?.error ?? null

  // FR-047: record reading for signed-in readers (the server ignores it while history is paused).
  useEffect(() => {
    if (article && isReader && !offline) {
      api.post('/me/history', { articleId: article.id, kind: 'READ' }).catch(() => {})
    }
  }, [article, isReader, offline])

  const originalBody = useMemo(
    () => (article?.fullTextStatus === 'AVAILABLE' && article.fullTextHtml ? sanitize(article.fullTextHtml) : null),
    [article],
  )

  // A machine translation shown in place of the original (SRS amendment 2).
  const [translated, setTranslated] = useState<{ id: number; value: Translation } | null>(null)
  const translation = article && translated?.id === article.id ? translated.value : null
  const translatedBody = useMemo(
    () => (translation?.bodyHtml ? sanitize(translation.bodyHtml) : null),
    [translation],
  )
  const bodyHtml = translation ? translatedBody : originalBody

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
  if (error === 'expired') return <p role="alert" className="notice">{t('offline.expired')}</p>
  if (error) return <p role="alert" className="error">{t('common.error')}</p>
  if (!article) return <p role="status">{t('common.loading')}</p>

  // FR-059: full text is read aloud only when narration is permitted; otherwise headline and summary.
  const narrateFull = article.fullTextNarrationPermitted && article.fullTextHtml !== null
  const spoken = narrateFull
    ? `${article.title}. ${plainText(article.fullTextHtml ?? '')}`
    : [article.title, article.excerpt].filter(Boolean).join('. ')
  const publisher = article.publisher.name
  const shown = translation
    ? { title: translation.title, excerpt: translation.excerpt, lang: translation.language, dir: translation.direction }
    : { title: article.title, excerpt: article.excerpt, lang: article.language, dir: article.direction }

  return (
    <article className="article">
      {offline && (
        <p role="status" className="notice">
          {t('offline.viewingCopy', { date: formatDateTime(offline.savedAt) })}
        </p>
      )}
      <header>
        {article.categories.length > 0 && <p className="kicker">{categoryName(article.categories[0])}</p>}
        <h1 lang={shown.lang} dir={shown.dir}>
          {shown.title}
        </h1>
        {shown.excerpt && bodyHtml && (
          <p className="standfirst" lang={shown.lang} dir={shown.dir}>
            {shown.excerpt}
          </p>
        )}
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
        {article.categories.length > 1 && (
          <p className="hint">
            {t('article.categories')}: {article.categories.map(categoryName).join(', ')}
          </p>
        )}
      </header>

      {article.imageUrl && !offline && (
        <figure className="hero">
          <img
            src={article.imageUrl}
            alt=""
            decoding="async"
            referrerPolicy="no-referrer"
            onError={(e) => {
              // Hide the figure and its credit if the publisher's image fails to load.
              const figure = e.currentTarget.parentElement
              if (figure) figure.hidden = true
            }}
          />
          <figcaption>{t('article.imageCredit', { publisher })}</figcaption>
        </figure>
      )}

      <div className="article-tools">
        <ListenButton
          text={spoken}
          language={article.language}
          summaryOnly={!narrateFull}
          onStart={() => {
            if (isReader) api.post('/me/history', { articleId: article.id, kind: 'LISTEN' }).catch(() => {})
          }}
        />
        {!offline && <SaveControls articleId={article.id} />}
        {!offline && <DownloadButton articleId={article.id} />}
      </div>

      {!offline && (
        <TranslateBar
          article={article}
          translation={translation}
          onChange={(value) => setTranslated(value ? { id: article.id, value } : null)}
        />
      )}

      {shown.excerpt && !bodyHtml && (
        <p className="lead" lang={shown.lang} dir={shown.dir}>
          {shown.excerpt}
        </p>
      )}

      {translation && !translation.bodyHtml && originalBody && (
        <p className="hint">{t('translate.headlineOnly')}</p>
      )}

      {bodyHtml ? (
        <div
          className="article-body"
          lang={shown.lang}
          dir={shown.dir}
          dangerouslySetInnerHTML={{ __html: bodyHtml }}
        />
      ) : translation && originalBody ? null : (
        <p className="notice">
          {offline?.level === 'METADATA'
            ? t('offline.metadataOnly')
            : article.fullTextStatus === 'NOT_PERMITTED'
              ? t('article.fullTextNotPermitted')
              : t('article.fullTextNotProvided')}{' '}
          <a href={article.sourceUrl} target="_blank" rel="noopener noreferrer">
            {t('article.readAtSource', { publisher })}
            <span className="visually-hidden"> {t('common.opensNewTab')}</span>
          </a>
        </p>
      )}

      <VideoList videos={article.videos ?? []} />

      {!offline && (
        <details className="report-box">
          <summary>{t('report.open')}</summary>
          <ReportForm articleId={article.id} />
        </details>
      )}
    </article>
  )
}
