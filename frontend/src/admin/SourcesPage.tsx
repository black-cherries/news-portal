import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { api } from '../api/client'
import type { FeedHealthRow, SourceView } from './types'
import { Badge, ErrorText, Section, Time } from './shared'
import { useLoad, useRoles } from './hooks'

/** All publishers with their effective rights at a glance. */
export function SourcesPage() {
  const { has } = useRoles()
  const { data, error } = useLoad<SourceView[]>('/admin/sources')

  return (
    <>
      <h1>Publishers</h1>
      <ErrorText error={error} />
      {data && data.length === 0 && <p>No publishers yet.</p>}
      {data && data.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Publishers, their status and the uses currently allowed</caption>
            <thead>
              <tr>
                <th scope="col">Publisher</th>
                <th scope="col">Status</th>
                <th scope="col">Uses allowed now</th>
                <th scope="col">Feeds</th>
              </tr>
            </thead>
            <tbody>
              {data.map((s) => (
                <tr key={s.id}>
                  <td>
                    <Link to={`/admin/sources/${s.id}`}>{s.name}</Link>
                    <div className="hint">
                      {s.code} · {s.primaryLanguage}
                    </div>
                  </td>
                  <td>
                    <Badge value={s.status} />
                  </td>
                  <td>
                    {s.rights.filter((r) => r.allowed).map((r) => r.use.toLowerCase().replaceAll('_', ' ')).join(', ') ||
                      'none'}
                  </td>
                  <td>
                    {s.feeds.map((f) => (
                      <div key={f.id}>
                        <Badge value={f.health} />
                      </div>
                    ))}
                    {s.feeds.length === 0 && <span className="hint">none</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {has('SOURCE_MANAGER') && <CreateSourceForm />}
    </>
  )
}

function CreateSourceForm() {
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    const value = (k: string) => String(f.get(k) ?? '').trim()
    try {
      const created = await api.post<SourceView>('/admin/sources', {
        code: value('code'),
        name: value('name'),
        homepageUrl: value('homepageUrl'),
        primaryLanguage: value('primaryLanguage'),
        termsUrl: value('termsUrl') || null,
        termsSummary: value('termsSummary') || null,
      })
      navigate(`/admin/sources/${created.id}`)
    } catch (err) {
      setError(err)
    }
  }

  return (
    <Section title="Add a publisher">
      <p className="hint">
        New publishers start as proposed. Record a METADATA permission, then activate. Feed availability is not proof
        of permission (FR-007).
      </p>
      <form onSubmit={submit} className="stack">
        <label className="field">
          Code (lowercase letters, digits, hyphens)
          <input name="code" required pattern="[a-z0-9][a-z0-9-]{1,59}" />
        </label>
        <label className="field">
          Name
          <input name="name" required maxLength={200} />
        </label>
        <label className="field">
          Homepage URL
          <input name="homepageUrl" type="url" required />
        </label>
        <label className="field">
          Primary language
          <select name="primaryLanguage" defaultValue="en">
            <option value="en">English</option>
            <option value="hi">Hindi</option>
            <option value="ur">Urdu</option>
            <option value="ar">Arabic</option>
          </select>
        </label>
        <label className="field">
          Terms URL (optional)
          <input name="termsUrl" type="url" />
        </label>
        <label className="field">
          Terms summary (optional)
          <textarea name="termsSummary" rows={3} />
        </label>
        <button type="submit">Add publisher</button>
        <ErrorText error={error} />
      </form>
    </Section>
  )
}

/** FR-079: every feed, worst first, with rights restrictions shown separately from failures. */
export function HealthPage() {
  const { data, error } = useLoad<FeedHealthRow[]>('/admin/source-health')
  return (
    <>
      <h1>Source health</h1>
      <p className="hint">
        Failing: 3+ failures in a row or never succeeded. Stale: no success for 3 refresh intervals (at least 2 h).
        Recovering: succeeding again after a failure in the last 24 h. Rights restricted is not a fetch failure.
      </p>
      <ErrorText error={error} />
      {data && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Health</th>
                <th scope="col">Publisher</th>
                <th scope="col">Last outcome</th>
                <th scope="col">Last success</th>
                <th scope="col">Next run</th>
              </tr>
            </thead>
            <tbody>
              {data.map((r) => (
                <tr key={r.feedId}>
                  <td>
                    <Badge value={r.health} />
                  </td>
                  <td>
                    <Link to={`/admin/sources/${r.sourceId}`}>{r.sourceName}</Link>
                    <div className="hint url">{r.feedUrl}</div>
                  </td>
                  <td>
                    {r.lastOutcome ?? '—'}
                    {r.consecutiveFailures > 0 && <div className="hint">{r.consecutiveFailures} failure(s) in a row</div>}
                  </td>
                  <td>
                    <Time value={r.lastSuccessAt} />
                  </td>
                  <td>
                    <Time value={r.nextRunAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && data.length === 0 && <p>No feeds configured.</p>}
    </>
  )
}
