import { useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, query } from '../api/client'
import { REPORT_TYPES } from '../api/types'
import { REPORT_RESOLUTIONS, type StaffReport, type StaffReportPage } from './types'
import { Badge, ErrorText, Section, Time } from './shared'
import { useLoad, useRoles } from './hooks'

/** Report queue for content reviewers (FR-070..FR-073). */
export function ReportsAdminPage() {
  const { has } = useRoles()
  const [params, setParams] = useSearchParams()
  const page = Number(params.get('page') ?? 0)
  const path = has('CONTENT_REVIEWER')
    ? '/admin/reports' +
      query({ status: params.get('status'), type: params.get('type'), mine: params.get('mine'), page, size: 25 })
    : null
  const { data, error } = useLoad<StaffReportPage>(path)

  if (!has('CONTENT_REVIEWER')) {
    return (
      <>
        <h1>Reports</h1>
        <p>Only content reviewers handle reports. Counts are on the dashboard.</p>
      </>
    )
  }

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('page')
    setParams(next)
  }

  return (
    <>
      <h1>Reports</h1>
      <div className="filters">
        <label className="field">
          Status
          <select value={params.get('status') ?? ''} onChange={(e) => set('status', e.target.value)}>
            <option value="">Any</option>
            <option value="RECEIVED">Received</option>
            <option value="UNDER_REVIEW">Under review</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </label>
        <label className="field">
          Type
          <select value={params.get('type') ?? ''} onChange={(e) => set('type', e.target.value)}>
            <option value="">Any</option>
            {REPORT_TYPES.map((t) => (
              <option key={t} value={t}>
                {t.toLowerCase().replaceAll('_', ' ')}
              </option>
            ))}
          </select>
        </label>
        <label className="check">
          <input type="checkbox" checked={params.get('mine') === 'true'} onChange={(e) => set('mine', e.target.checked ? 'true' : '')} />
          Assigned to me
        </label>
      </div>
      <ErrorText error={error} />
      {data && data.items.length === 0 && <p>No reports match.</p>}
      {data && data.items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Reference</th>
                <th scope="col">Type</th>
                <th scope="col">Status</th>
                <th scope="col">Article</th>
                <th scope="col">Assigned</th>
                <th scope="col">Received</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((r) => (
                <tr key={r.id}>
                  <td>
                    <Link to={`/admin/reports/${r.id}`}>{r.reference}</Link>
                  </td>
                  <td>{r.type.toLowerCase().replaceAll('_', ' ')}</td>
                  <td>
                    <Badge value={r.status} />
                  </td>
                  <td>{r.articleTitle ?? <span className="hint">—</span>}</td>
                  <td>{r.assignedToEmail ?? <span className="hint">unassigned</span>}</td>
                  <td>
                    <Time value={r.createdAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="actions">
        {page > 0 && (
          <button type="button" className="secondary" onClick={() => set('page', String(page - 1))}>
            Previous
          </button>
        )}
        {data?.hasMore && (
          <button
            type="button"
            className="secondary"
            onClick={() => {
              const next = new URLSearchParams(params)
              next.set('page', String(page + 1))
              setParams(next)
            }}
          >
            Next
          </button>
        )}
      </div>
    </>
  )
}

export function ReportAdminDetailPage() {
  const { id } = useParams()
  const { data: report, error, reload } = useLoad<StaffReport>(`/admin/reports/${encodeURIComponent(id ?? '')}`)
  const [actionError, setActionError] = useState<unknown>(null)
  const [status, setStatus] = useState<'UNDER_REVIEW' | 'RESOLVED'>('UNDER_REVIEW')

  if (error) return <ErrorText error={error} />
  if (!report) return <p role="status">Loading…</p>

  const assignToMe = async () => {
    try {
      await api.post(`/admin/reports/${report.id}/assign`, {})
      setActionError(null)
      await reload()
    } catch (err) {
      setActionError(err)
    }
  }

  const changeStatus = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    const f = new FormData(form)
    try {
      await api.post(`/admin/reports/${report.id}/status`, {
        status: target,
        resolution: target === 'RESOLVED' ? String(f.get('resolution')) : null,
        publicNote: String(f.get('publicNote') ?? '').trim() || null,
        internalNote: String(f.get('internalNote') ?? '').trim() || null,
      })
      form.reset()
      setActionError(null)
      await reload()
    } catch (err) {
      setActionError(err)
    }
  }

  // Which moves are allowed from here; the selected value is kept within them.
  const options: ('UNDER_REVIEW' | 'RESOLVED')[] =
    report.status === 'RECEIVED' ? ['UNDER_REVIEW', 'RESOLVED'] : report.status === 'UNDER_REVIEW' ? ['RESOLVED'] : ['UNDER_REVIEW']
  const target = options.includes(status) ? status : options[0]

  return (
    <>
      <p>
        <Link to="/admin/reports">All reports</Link>
      </p>
      <h1>
        {report.reference} <Badge value={report.status} />
      </h1>
      <dl className="timestamps">
        <dt>Type</dt>
        <dd>{report.type.toLowerCase().replaceAll('_', ' ')}</dd>
        <dt>Reporter</dt>
        <dd>
          {report.reporterType === 'READER' ? 'Registered reader' : 'Guest or deleted account'}
          {report.notifyMe && ' (gets email updates)'}
        </dd>
        <dt>Article</dt>
        <dd>
          {report.articleId ? (
            <Link to={`/articles/${report.articleId}`}>{report.articleTitle}</Link>
          ) : (
            '—'
          )}
          {report.sourceCode && <span className="hint"> · {report.sourceCode}</span>}
        </dd>
        <dt>Page</dt>
        <dd className="url">{report.pageUrl ?? '—'}</dd>
        <dt>Interface language</dt>
        <dd>{report.uiLanguage ?? '—'}</dd>
        <dt>Assigned to</dt>
        <dd>
          {report.assignedToEmail ?? 'unassigned'}{' '}
          <button type="button" className="secondary small" onClick={() => void assignToMe()}>
            Assign to me
          </button>
        </dd>
        <dt>Received</dt>
        <dd>
          <Time value={report.createdAt} />
        </dd>
      </dl>
      <Section title="Description">
        {/* Reporter text is shown as plain text, in its own direction. */}
        <p dir="auto" className="panel">
          {report.description}
        </p>
        <p className="hint">Reports never change an article automatically (FR-073). Make any fix through the normal process.</p>
      </Section>

      <Section title="Update status">
        <form onSubmit={changeStatus} className="stack">
          <label className="field">
            New status
            <select value={target} onChange={(e) => setStatus(e.target.value as 'UNDER_REVIEW' | 'RESOLVED')}>
              {options.map((s) => (
                <option key={s} value={s}>
                  {s === 'UNDER_REVIEW' ? (report.status === 'RESOLVED' ? 'Reopen (under review)' : 'Under review') : 'Resolved'}
                </option>
              ))}
            </select>
          </label>
          {target === 'RESOLVED' && (
            <label className="field">
              Resolution
              <select name="resolution" defaultValue="FIXED">
                {REPORT_RESOLUTIONS.map((r) => (
                  <option key={r} value={r}>
                    {r.toLowerCase().replaceAll('_', ' ')}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="field">
            Note to the reporter (optional; emailed if they asked for updates)
            <textarea name="publicNote" rows={2} maxLength={1000} />
          </label>
          <label className="field">
            Internal note (staff only)
            <textarea name="internalNote" rows={2} maxLength={2000} />
          </label>
          <button type="submit">Save status</button>
          <ErrorText error={actionError} />
        </form>
      </Section>

      <Section title="History">
        <ol>
          {report.history.map((h, i) => (
            <li key={i}>
              <Time value={h.at} /> · {h.fromStatus ?? 'new'} → {h.toStatus}
              {h.changedBy && <> · {h.changedBy}</>}
              {h.publicNote && <div>To reporter: {h.publicNote}</div>}
              {h.internalNote && <div className="hint">Internal: {h.internalNote}</div>}
            </li>
          ))}
        </ol>
      </Section>
    </>
  )
}
