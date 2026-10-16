import type { ArticleSummary } from '../api/types'
import { errorKey } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'
import { ArticleCard } from './ArticleCard'

interface Props {
  items: ArticleSummary[]
  loading: boolean
  error: unknown
  hasMore: boolean
  onLoadMore: () => void
  /** Show the first item large, as the lead story. */
  featured?: boolean
}

export function ArticleList({ items, loading, error, hasMore, onLoadMore, featured }: Props) {
  const { t } = useI18n()
  const [lead, ...rest] = featured ? items : [undefined, ...items]
  return (
    <section aria-busy={loading} className="article-list">
      {error ? (
        <p role="alert" className="error">
          {t(errorKey(error))}
        </p>
      ) : null}
      {!loading && !error && items.length === 0 && <p className="empty">{t('feed.noResults')}</p>}
      {lead && (
        <div className="lead-slot">
          <ArticleCard article={lead} variant="lead" />
        </div>
      )}
      <ul className="card-grid">
        {rest.map((a) =>
          a ? (
            <li key={a.id}>
              <ArticleCard article={a} />
            </li>
          ) : null,
        )}
      </ul>
      {loading && (
        <p role="status" className="loading">
          {t('common.loading')}
        </p>
      )}
      {hasMore && !loading && (
        <div className="load-more">
          <button type="button" className="secondary" onClick={onLoadMore}>
            {t('common.loadMore')}
          </button>
        </div>
      )}
    </section>
  )
}
