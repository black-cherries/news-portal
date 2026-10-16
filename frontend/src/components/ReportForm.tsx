import { useId, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ApiError, api } from '../api/client'
import { REPORT_TYPES, type ReportType } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { errorKey } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'
import type { MessageKey } from '../i18n/messages'

interface Props {
  articleId?: number
  /** Report type preselected in the form. */
  initialType?: ReportType
}

/** FR-070: structured report. Guests can send one; signed-in readers can also follow it. */
export function ReportForm({ articleId, initialType = articleId ? 'CORRECTION_REQUEST' : 'TECHNICAL' }: Props) {
  const { t, lang } = useI18n()
  const { me, isReader } = useAuth()
  const ids = { type: useId(), description: useId(), hint: useId(), notify: useId() }
  const [sent, setSent] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [type, setType] = useState<ReportType>(initialType)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    setError(null)
    try {
      const result = await api.post<{ reference: string }>(
        '/reports',
        {
          type,
          articleId: articleId ?? null,
          description: String(form.get('description') ?? ''),
          pageUrl: window.location.pathname,
          notifyMe: form.get('notify') === 'on',
        },
        { 'X-UI-Language': lang },
      )
      setSent(result.reference)
    } catch (err) {
      setError(err instanceof ApiError && err.status === 400 ? t('auth.checkDetails') : t(errorKey(err)))
    }
  }

  if (sent) {
    return (
      <p role="status" className="notice">
        {isReader ? t('report.sent', { reference: sent }) : t('report.sentGuest', { reference: sent })}{' '}
        {isReader && <Link to="/reports">{t('report.mine')}</Link>}
      </p>
    )
  }

  return (
    <form onSubmit={submit} className="stack">
      <div className="field">
        <label htmlFor={ids.type}>{t('report.type')}</label>
        <select id={ids.type} value={type} onChange={(e) => setType(e.target.value as ReportType)}>
          {REPORT_TYPES.map((r) => (
            <option key={r} value={r}>
              {t(`report.type.${r}` as MessageKey)}
            </option>
          ))}
        </select>
      </div>
      {type === 'CORRECTION_REQUEST' && <p className="hint">{t('report.correctionNote')}</p>}
      <div className="field">
        <label htmlFor={ids.description}>{t('report.description')}</label>
        <textarea
          id={ids.description}
          name="description"
          required
          minLength={10}
          maxLength={2000}
          rows={4}
          aria-describedby={ids.hint}
        />
        <p id={ids.hint} className="hint">
          {t('report.descriptionHint')}
        </p>
      </div>
      {isReader && (
        <div className="check">
          <input id={ids.notify} type="checkbox" name="notify" />
          <label htmlFor={ids.notify}>{t('report.notify')}</label>
        </div>
      )}
      <button type="submit" disabled={me === undefined}>
        {t('report.submit')}
      </button>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </form>
  )
}
