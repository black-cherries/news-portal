import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { ArticlePage } from '../api/types'
import { ArticleCard } from '../components/ArticleCard'
import { ArticleList } from '../components/ArticleList'
import { useAuth } from '../auth/AuthProvider'
import { useArticlePages } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'

/** Rule-based For You feed (FR-024) with a separate, labelled other-languages section (FR-029). */
export function ForYouPage() {
  const { t } = useI18n()
  const { isReader } = useAuth()
  const list = useArticlePages(isReader ? '/me/for-you' : null)
  const [others, setOthers] = useState<ArticlePage | null>(null)

  useEffect(() => {
    if (!isReader) return
    api.get<ArticlePage>('/me/for-you/other-languages?size=5').then(setOthers, () => setOthers(null))
  }, [isReader])

  if (!isReader) {
    return (
      <>
        <h1>{t('feed.forYouTitle')}</h1>
        <p>
          <Link to="/sign-in">{t('feed.signInForYou')}</Link>
        </p>
      </>
    )
  }

  return (
    <>
      <h1>{t('feed.forYouTitle')}</h1>
      <p className="hint">
        {t('feed.forYouHint')} <Link to="/settings">{t('nav.settings')}</Link>
      </p>
      <div className="with-aside">
        <ArticleList
          items={list.items}
          loading={list.loading}
          error={list.error}
          hasMore={list.nextCursor !== null}
          onLoadMore={() => void list.loadMore()}
        />
        {others && others.items.length > 0 && (
          <aside aria-labelledby="other-languages">
            <h2 id="other-languages">{t('feed.otherLanguages')}</h2>
            <ul className="card-list compact">
              {others.items.map((a) => (
                <li key={a.id}>
                  <ArticleCard article={a} />
                </li>
              ))}
            </ul>
          </aside>
        )}
      </div>
    </>
  )
}
