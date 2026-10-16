import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { ArticleSummary } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'
import { Timestamps } from './Timestamps'

/** One article in a list. The article text carries its own language and direction. */
export function ArticleCard({ article, extra }: { article: ArticleSummary; extra?: ReactNode }) {
  const { categoryName } = useI18n()
  return (
    <article className="card">
      <h2 className="card-title" lang={article.language} dir={article.direction}>
        <Link to={`/articles/${article.id}`}>{article.title}</Link>
      </h2>
      {article.excerpt && (
        <p className="card-excerpt" lang={article.language} dir={article.direction}>
          {article.excerpt}
        </p>
      )}
      <p className="card-meta">
        <span className="publisher">{article.publisher.name}</span>
        <span aria-hidden="true"> · </span>
        <Timestamps
          publishedAt={article.publishedAt}
          publisherUpdatedAt={article.publisherUpdatedAt}
          ingestedAt={article.ingestedAt}
          compact
        />
        {article.categories.length > 0 && (
          <>
            <span aria-hidden="true"> · </span>
            <span>{article.categories.map(categoryName).join(', ')}</span>
          </>
        )}
      </p>
      {extra}
    </article>
  )
}
