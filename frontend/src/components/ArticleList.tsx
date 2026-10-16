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
}

export function ArticleList({ items, loading, error, hasMore, onLoadMore }: Props) {
  const { t } = useI18n()
  return (
    <section aria-busy={loading}>
      {error ? (
        <p role="alert" className="error">
          {t(errorKey(error))}
        </p>
      ) : null}
      {!loading && !error && items.length === 0 && <p>{t('feed.noResults')}</p>}
      <ul className="card-list">
        {items.map((a) => (
          <li key={a.id}>
            <ArticleCard article={a} />
          </li>
        ))}
      </ul>
      {loading && <p role="status">{t('common.loading')}</p>}
      {hasMore && !loading && (
        <button type="button" className="secondary" onClick={onLoadMore}>
          {t('common.loadMore')}
        </button>
      )}
    </section>
  )
}
