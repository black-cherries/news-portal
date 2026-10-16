import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, api } from '../api/client'
import { useI18n } from '../i18n/I18nProvider'

type Kind = 'DIGEST' | 'ALERTS' | 'ALL'

/** Reads #token=…&kind=… from the link in a notification email. */
function linkFromHash(): { token: string; kind: Kind | null } {
  const params = new URLSearchParams(window.location.hash.slice(1))
  const kind = params.get('kind')
  return { token: params.get('token') ?? '', kind: kind === 'DIGEST' || kind === 'ALERTS' ? kind : null }
}

/**
 * Works without signing in. Nothing changes until the reader presses a
 * button, so mail scanners that open links do not unsubscribe anyone.
 */
export function UnsubscribePage() {
  const { t } = useI18n()
  const [link] = useState(linkFromHash)
  const [result, setResult] = useState<'idle' | 'busy' | 'done' | 'invalid' | 'error'>(
    link.token ? 'idle' : 'invalid',
  )

  useEffect(() => {
    // Drop the token from the address bar and history.
    window.history.replaceState(null, '', window.location.pathname)
  }, [])

  const stop = async (kind: Kind) => {
    setResult('busy')
    try {
      await api.post('/notifications/unsubscribe', { token: link.token, kind })
      setResult('done')
    } catch (err) {
      setResult(err instanceof ApiError && err.status === 404 ? 'invalid' : 'error')
    }
  }

  const kinds: Kind[] = link.kind ? [link.kind, 'ALL'] : ['DIGEST', 'ALERTS', 'ALL']
  const labels = { DIGEST: 'unsubscribe.digest', ALERTS: 'unsubscribe.alerts', ALL: 'unsubscribe.all' } as const

  return (
    <div className="narrow">
      <h1>{t('unsubscribe.title')}</h1>
      {(result === 'idle' || result === 'busy' || result === 'error') && (
        <div className="stack">
          <p>{t('unsubscribe.intro')}</p>
          {kinds.map((k, i) => (
            <button
              key={k}
              type="button"
              className={i === 0 ? undefined : 'secondary'}
              disabled={result === 'busy'}
              onClick={() => void stop(k)}
            >
              {t(labels[k])}
            </button>
          ))}
          {result === 'error' && <p role="alert">{t('common.error')}</p>}
        </div>
      )}
      {result === 'done' && (
        <p role="status">
          {t('unsubscribe.done')} <Link to="/settings">{t('settings.title')}</Link>
        </p>
      )}
      {result === 'invalid' && <p role="alert">{t('unsubscribe.invalid')}</p>}
    </div>
  )
}
