import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { MyReport } from '../api/types'
import { ReportForm } from '../components/ReportForm'
import { errorKey } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'
import type { MessageKey } from '../i18n/messages'

/** General "report a problem" page, linked from the footer. */
export function ReportPage() {
  const { t } = useI18n()
  return (
    <div className="narrow">
      <h1>{t('report.title')}</h1>
      <ReportForm />
    </div>
  )
}

/** FR-071/FR-072: a reader's own reports and their public updates. Never reachable by reference alone. */
export function MyReportsPage() {
  const { t, formatDateTime } = useI18n()
  const [reports, setReports] = useState<MyReport[] | null>(null)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    api.get<MyReport[]>('/me/reports').then(setReports, setError)
  }, [])

  const toggleNotify = async (report: MyReport) => {
    try {
      await api.put(`/me/reports/${encodeURIComponent(report.reference)}/notify`, { notifyMe: !report.notifyMe })
      setReports((list) =>
        list ? list.map((r) => (r.reference === report.reference ? { ...r, notifyMe: !r.notifyMe } : r)) : list,
      )
    } catch (err) {
      setError(err)
    }
  }

  return (
    <>
      <h1>{t('report.mine')}</h1>
      <p>
        <Link to="/report">{t('report.title')}</Link>
      </p>
      {error ? (
        <p role="alert" className="error">
          {t(errorKey(error))}
        </p>
      ) : null}
      {reports && reports.length === 0 && <p>{t('report.noReports')}</p>}
      <ul className="plain-list">
        {reports?.map((r) => (
          <li key={r.reference} className="card">
            <h2 className="card-title">
              {t(`report.type.${r.type}` as MessageKey)} · <span dir="ltr">{r.reference}</span>
            </h2>
            <p className="card-meta">
              <strong>{t(`report.status.${r.status}` as MessageKey)}</strong> ·{' '}
              <time dateTime={r.createdAt}>{formatDateTime(r.createdAt)}</time>
              {r.articleId && r.articleTitle && (
                <>
                  {' '}
                  · <Link to={`/articles/${r.articleId}`}>{r.articleTitle}</Link>
                </>
              )}
            </p>
            <p>{r.description}</p>
            <details>
              <summary>{t('report.updates')}</summary>
              <ol>
                {r.updates.map((u, i) => (
                  <li key={i}>
                    <time dateTime={u.at}>{formatDateTime(u.at)}</time>: {t(`report.status.${u.status}` as MessageKey)}
                    {u.note && <> — {u.note}</>}
                  </li>
                ))}
              </ol>
            </details>
            <label className="check">
              <input type="checkbox" checked={r.notifyMe} onChange={() => void toggleNotify(r)} />
              <span>{t('report.notify')}</span>
            </label>
          </li>
        ))}
      </ul>
    </>
  )
}
