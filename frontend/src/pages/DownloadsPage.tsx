import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useI18n } from '../i18n/I18nProvider'
import { isUsable, lastSync, removeDownload, syncDownloads } from '../offline/downloads'
import { offlineStore, type OfflineArticle } from '../offline/store'

interface Usage {
  used: number
  quota: number
}

/** FR-062: manage downloads and see storage use where the browser reports it. */
export function DownloadsPage() {
  const { t, formatDateTime, formatBytes } = useI18n()
  const [items, setItems] = useState<OfflineArticle[] | null>(null)
  const [usage, setUsage] = useState<Usage | null>(null)
  const [checked, setChecked] = useState<string | null>(lastSync)
  const [status, setStatus] = useState<string | null>(null)
  const [persisted, setPersisted] = useState(false)

  const load = useCallback(async () => {
    const list = await offlineStore().list().catch(() => [])
    list.sort((a, b) => b.savedAt.localeCompare(a.savedAt))
    setItems(list)
    const estimate = await navigator.storage?.estimate?.().catch(() => null)
    setUsage(estimate?.usage !== undefined && estimate.quota ? { used: estimate.usage, quota: estimate.quota } : null)
    setPersisted(Boolean(await navigator.storage?.persisted?.().catch(() => false)))
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const checkNow = async () => {
    try {
      const r = await syncDownloads()
      setChecked(lastSync())
      setStatus(t('offline.syncResult', { checked: r.checked, removed: r.removed, reduced: r.reduced }))
    } catch {
      setStatus(t('common.error'))
    }
    await load()
  }

  const remove = async (id: number) => {
    await removeDownload(id)
    await load()
  }

  const removeAll = async () => {
    await offlineStore().clear()
    await load()
  }

  const persist = async () => {
    setPersisted(Boolean(await navigator.storage?.persist?.().catch(() => false)))
  }

  return (
    <>
      <h1>{t('offline.title')}</h1>
      <p>{t('offline.intro')}</p>
      <p className="notice">{t('offline.restrictionsNote')}</p>
      <p className="hint">
        {usage
          ? t('offline.usage', { used: formatBytes(usage.used), quota: formatBytes(usage.quota) })
          : t('offline.usageUnknown')}
        {checked && <> · {t('offline.lastCheck', { date: formatDateTime(checked) })}</>}
      </p>
      <div className="actions">
        <button type="button" className="secondary" onClick={() => void checkNow()}>
          {t('offline.checkNow')}
        </button>
        {'storage' in navigator && 'persist' in navigator.storage && !persisted && (
          <button type="button" className="secondary" onClick={() => void persist()}>
            {t('offline.persist')}
          </button>
        )}
        {items && items.length > 0 && (
          <button type="button" className="danger" onClick={() => void removeAll()}>
            {t('offline.removeAll')}
          </button>
        )}
      </div>
      {persisted && <p className="hint">{t('offline.persisted')}</p>}
      {status && <p role="status">{status}</p>}

      {items === null && <p role="status">{t('common.loading')}</p>}
      {items && items.length === 0 && <p>{t('offline.empty')}</p>}
      {items && items.length > 0 && (
        <ul className="plain-list downloads">
          {items.map((item) => (
            <li key={item.id}>
              <Link to={`/articles/${item.id}`} lang={item.article.language} dir={item.article.direction}>
                {item.article.title}
              </Link>
              <div className="hint">
                {item.article.publisher.name} ·{' '}
                {isUsable(item)
                  ? item.level === 'FULL_TEXT'
                    ? t('offline.full')
                    : t('offline.metadata')
                  : t('offline.needsCheck')}{' '}
                · {t('offline.itemInfo', { size: formatBytes(item.bytes), date: formatDateTime(item.savedAt) })}
              </div>
              <button type="button" className="secondary small" onClick={() => void remove(item.id)}>
                {t('offline.remove')}
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
