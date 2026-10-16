import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, query } from '../api/client'
import type { ArticlePage, ArticleSummary } from '../api/types'
import type { AlertPage, AlertView } from './types'
import { Badge, ErrorText, NotesAction, Section, Time } from './shared'
import { useLoad, useRoles } from './hooks'

/** Breaking-news alerts (FR-065, FR-067). Content reviewers only. */
export function AlertsPage() {
  const { has } = useRoles()
  const { data, error, reload } = useLoad<AlertPage>(has('CONTENT_REVIEWER') ? '/admin/alerts?size=50' : null)

  if (!has('CONTENT_REVIEWER')) {
    return (
      <>
        <h1>Breaking-news alerts</h1>
        <p>Only content reviewers send breaking-news alerts.</p>
      </>
    )
  }

  return (
    <>
      <h1>Breaking-news alerts</h1>
      <p className="hint">
        Alerts go by email to readers who turned them on and whose languages and topics match the article. Each alert
        waits 2 minutes before sending, so a mistake can be cancelled. The same article or headline cannot be alerted
        twice in 24 hours, and readers get at most a few alerts a day. Copies of the same headline from other
        publishers are usually syndication: they are not independent confirmation (FR-067).
      </p>
      <NewAlert onCreated={reload} />
      <Section title="Recent alerts">
        <ErrorText error={error} />
        {data && data.items.length === 0 && <p>No alerts yet.</p>}
        {data && data.items.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Status</th>
                  <th scope="col">Article</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Created</th>
                  <th scope="col">Sent</th>
                  <th scope="col">Recipients</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((a) => (
                  <AlertRow key={a.id} alert={a} onChanged={reload} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>
    </>
  )
}

function AlertRow({ alert: a, onChanged }: { alert: AlertView; onChanged: () => Promise<void> }) {
  return (
    <tr>
      <td>
        <Badge value={a.status} />
      </td>
      <td>
        {a.articleId ? (
          <Link to={`/articles/${a.articleId}`} lang={a.articleLanguage ?? undefined} dir="auto">
            {a.articleTitle}
          </Link>
        ) : (
          <span className="hint">article removed</span>
        )}
        {a.sourceName && <div className="hint">{a.sourceName}</div>}
      </td>
      <td>
        {a.reason}
        {a.cancelReason && <div className="hint">Cancelled: {a.cancelReason}</div>}
      </td>
      <td>
        <Time value={a.createdAt} />
        <div className="hint">{a.createdBy ?? '—'}</div>
      </td>
      <td>{a.status === 'QUEUED' ? <span className="hint">after <Time value={a.sendAfter} /></span> : <Time value={a.sentAt} />}</td>
      <td>
        {a.recipientCount}
        {a.skippedCount > 0 && <div className="hint">{a.skippedCount} over daily limit</div>}
      </td>
      <td>
        {a.status === 'QUEUED' && (
          <NotesAction
            label="Cancel alert"
            danger
            placeholder="Why cancel? (recorded in the audit log)"
            onSubmit={async (reason) => {
              await api.post(`/admin/alerts/${a.id}/cancel`, { reason })
              await onChanged()
            }}
          />
        )}
      </td>
    </tr>
  )
}

/** Find an article with the public search, then queue an alert for it. */
function NewAlert({ onCreated }: { onCreated: () => Promise<void> }) {
  const [results, setResults] = useState<ArticleSummary[] | null>(null)
  const [chosen, setChosen] = useState<ArticleSummary | null>(null)
  const [error, setError] = useState<unknown>(null)

  const search = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const q = String(new FormData(e.currentTarget).get('q') ?? '').trim()
    try {
      const page = await api.get<ArticlePage>('/articles' + query({ q: q || null, size: 10 }))
      setResults(page.items)
      setError(null)
    } catch (err) {
      setError(err)
    }
  }

  const create = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (!chosen) return
    const form = e.currentTarget
    try {
      await api.post('/admin/alerts', {
        articleId: chosen.id,
        reason: String(new FormData(form).get('reason') ?? '').trim(),
      })
      form.reset()
      setChosen(null)
      setResults(null)
      setError(null)
      await onCreated()
    } catch (err) {
      setError(err)
    }
  }

  return (
    <Section title="Send an alert">
      <form onSubmit={search} className="filters" role="search">
        <label className="field">
          Find the article (leave empty for the latest)
          <input name="q" type="search" maxLength={200} />
        </label>
        <button type="submit" className="secondary">
          Search
        </button>
      </form>
      {results && results.length === 0 && <p>No articles found.</p>}
      {results && results.length > 0 && !chosen && (
        <ul className="plain-list">
          {results.map((r) => (
            <li key={r.id}>
              <button type="button" className="secondary small" onClick={() => setChosen(r)}>
                Choose
              </button>{' '}
              <span lang={r.language} dir={r.direction}>
                {r.title}
              </span>{' '}
              <span className="hint">
                · {r.publisher.name} · {r.language} · <Time value={r.publishedAt ?? r.ingestedAt} />
              </span>
            </li>
          ))}
        </ul>
      )}
      {chosen && (
        <form onSubmit={create} className="stack">
          <p>
            Alert for:{' '}
            <strong lang={chosen.language} dir={chosen.direction}>
              {chosen.title}
            </strong>{' '}
            <span className="hint">· {chosen.publisher.name}</span>{' '}
            <button type="button" className="secondary small" onClick={() => setChosen(null)}>
              Change
            </button>
          </p>
          <label className="field">
            Why is this breaking news? (staff only; recorded in the audit log)
            <textarea name="reason" required rows={2} maxLength={500} />
          </label>
          <button type="submit">Queue alert</button>
        </form>
      )}
      <ErrorText error={error} />
    </Section>
  )
}
