import { useEffect, useState } from 'react'
import { download, NotDownloadableError, removeDownload } from '../offline/downloads'
import { offlineStore, type OfflineLevel } from '../offline/store'
import { useI18n } from '../i18n/I18nProvider'

/** Save or remove an offline copy of one article (FR-061, FR-062). */
export function DownloadButton({ articleId }: { articleId: number }) {
  const { t } = useI18n()
  const [saved, setSaved] = useState<OfflineLevel | null | undefined>(undefined)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    offlineStore()
      .get(articleId)
      .then(
        (item) => {
          if (!cancelled) setSaved(item?.level ?? null)
        },
        () => {
          if (!cancelled) setSaved(null)
        },
      )
    return () => {
      cancelled = true
    }
  }, [articleId])

  if (saved === undefined) return null

  const save = async () => {
    try {
      const item = await download(articleId)
      setSaved(item.level)
      setMessage(item.level === 'FULL_TEXT' ? t('offline.saved') : t('offline.savedMetadata'))
    } catch (err) {
      setMessage(err instanceof NotDownloadableError ? t('offline.notAllowed') : t('common.error'))
    }
  }

  const remove = async () => {
    await removeDownload(articleId)
    setSaved(null)
    setMessage(null)
  }

  return (
    <div className="download">
      {saved ? (
        <button type="button" className="secondary" onClick={() => void remove()}>
          {t('offline.remove')}
        </button>
      ) : (
        <button type="button" className="secondary" onClick={() => void save()}>
          {t('offline.download')}
        </button>
      )}
      {message && (
        <p role="status" className="hint">
          {message}
        </p>
      )}
    </div>
  )
}
