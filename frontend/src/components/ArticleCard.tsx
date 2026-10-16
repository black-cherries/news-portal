import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { ArticleSummary } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'
import { LANGUAGE_NAMES, isLang } from '../i18n/messages'

export type CardVariant = 'lead' | 'grid' | 'compact'

/**
 * One article in a list. The article text carries its own language and
 * direction. Lead images appear only when the publisher allows it (the API
 * sends {@code imageUrl} only then), and load without a referrer.
 */
export function ArticleCard({
  article,
  extra,
  variant = 'grid',
}: {
  article: ArticleSummary
  extra?: ReactNode
  variant?: CardVariant
}) {
  const { t, lang, categoryName, formatRelative, formatDateTime } = useI18n()
  const [imageFailed, setImageFailed] = useState(false)
  const showImage = variant !== 'compact' && article.imageUrl && !imageFailed
  // FR-017/FR-022: the publisher's time, or our ingestion time clearly labelled when unknown.
  const when = article.publishedAt ?? article.ingestedAt
  const kicker = article.categories[0]

  return (
    <article className={`card card-${variant}${showImage ? ' has-image' : ''}`}>
      {showImage && (
        <Link to={`/articles/${article.id}`} className="card-media" tabIndex={-1} aria-hidden="true">
          <img
            src={article.imageUrl ?? undefined}
            alt=""
            loading={variant === 'lead' ? 'eager' : 'lazy'}
            decoding="async"
            referrerPolicy="no-referrer"
            onError={() => setImageFailed(true)}
          />
        </Link>
      )}
      <div className="card-body">
        {kicker && <p className="kicker">{categoryName(kicker)}</p>}
        <h2 className="card-title" lang={article.language} dir={article.direction}>
          <Link to={`/articles/${article.id}`}>{article.title}</Link>
        </h2>
        {article.excerpt && variant !== 'compact' && (
          <p className="card-excerpt" lang={article.language} dir={article.direction}>
            {article.excerpt}
          </p>
        )}
        <p className="card-meta">
          <span className="publisher">{article.publisher.name}</span>
          <span aria-hidden="true">·</span>
          <time dateTime={when} title={formatDateTime(when)}>
            {article.publishedAt ? formatRelative(when) : `${t('article.ingested')} ${formatRelative(when)}`}
          </time>
          {article.language !== lang && (
            <span className="lang-pill" lang={article.language}>
              {isLang(article.language) ? LANGUAGE_NAMES[article.language] : article.language}
            </span>
          )}
        </p>
        {extra}
      </div>
    </article>
  )
}
