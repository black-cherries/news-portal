import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { api } from '../api/client'
import type { ContentUse, FeedView, JobView, QuarantineView, RightsView, SourceDetail, SourceView } from './types'
import { Badge, ErrorText, NotesAction, Section, Time } from './shared'
import { errorText, localToIso, useLoad, useRoles } from './hooks'

/**
 * One publisher: terms, lifecycle, rights per content use, feeds and the
 * review history. Changes need the SOURCE_MANAGER role and a reason.
 */
export function SourceDetailPage() {
  const { id } = useParams()
  const { has } = useRoles()
  const canEdit = has('SOURCE_MANAGER')
  const { data, error, reload } = useLoad<SourceDetail>(`/admin/sources/${encodeURIComponent(id ?? '')}`)

  if (error) {
    return (
      <>
        <ErrorText error={error} />
        <Link to="/admin/sources">All publishers</Link>
      </>
    )
  }
  if (!data) return <p role="status">Loading…</p>
  const s = data.source
  const post = (path: string, body: unknown) => api.post(`/admin/sources/${s.id}${path}`, body).then(reload)

  return (
    <>
      <p>
        <Link to="/admin/sources">All publishers</Link>
      </p>
      <h1>
        {s.name} <Badge value={s.status} />
      </h1>
      <p className="hint">
        {s.code} · {s.primaryLanguage} ·{' '}
        <a href={s.homepageUrl} target="_blank" rel="noopener noreferrer">
          {s.homepageUrl}
        </a>
        {s.statusReason && <> · last change: {s.statusReason}</>}
      </p>

      {canEdit && (
        <div className="actions">
          {s.status === 'PROPOSED' && (
            <NotesAction label="Activate" onSubmit={(notes) => post('/activate', { notes })} />
          )}
          {s.status === 'ACTIVE' && (
            <NotesAction label="Suspend publisher" danger onSubmit={(notes) => post('/suspend', { notes })} />
          )}
          {s.status === 'SUSPENDED' && (
            <NotesAction label="Restore publisher" onSubmit={(notes) => post('/restore', { notes })} />
          )}
          {s.status !== 'RETIRED' && (
            <NotesAction label="Retire publisher" danger onSubmit={(notes) => post('/retire', { notes })} />
          )}
        </div>
      )}

      <TermsSection source={s} canEdit={canEdit} onSaved={reload} />
      <RightsSection source={s} canEdit={canEdit} onChanged={reload} />
      <FeedsSection source={s} canEdit={canEdit} onChanged={reload} />

      <Section title="Review history">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Action</th>
                <th scope="col">Change</th>
                <th scope="col">Reviewer</th>
                <th scope="col">Notes</th>
              </tr>
            </thead>
            <tbody>
              {data.reviews.map((r) => (
                <tr key={r.id}>
                  <td>
                    <Time value={r.reviewedAt} />
                  </td>
                  <td>
                    {r.action.toLowerCase().replaceAll('_', ' ')}
                    {r.use && <div className="hint">{r.use}</div>}
                  </td>
                  <td>
                    {r.previousStatus ?? '—'} → {r.newStatus ?? '—'}
                    {r.expiresAt && (
                      <div className="hint">
                        expires <Time value={r.expiresAt} />
                      </div>
                    )}
                  </td>
                  <td>{r.reviewer}</td>
                  <td>
                    {r.notes}
                    {r.evidenceUrl && (
                      <div>
                        <a href={r.evidenceUrl} target="_blank" rel="noopener noreferrer">
                          evidence
                        </a>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </>
  )
}

function TermsSection({ source, canEdit, onSaved }: { source: SourceView; canEdit: boolean; onSaved: () => void }) {
  const [editing, setEditing] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    try {
      await api.patch(`/admin/sources/${source.id}/terms`, {
        termsUrl: String(f.get('termsUrl') ?? '').trim() || null,
        termsSummary: String(f.get('termsSummary') ?? '').trim() || null,
        notes: String(f.get('notes') ?? '').trim(),
      })
      setEditing(false)
      onSaved()
    } catch (err) {
      setError(err)
    }
  }

  return (
    <Section title="Terms">
      {!editing && (
        <>
          <p>
            {source.termsUrl ? (
              <a href={source.termsUrl} target="_blank" rel="noopener noreferrer">
                {source.termsUrl}
              </a>
            ) : (
              <span className="hint">No terms link recorded.</span>
            )}
          </p>
          {source.termsSummary && <p>{source.termsSummary}</p>}
          {canEdit && (
            <button type="button" className="secondary" onClick={() => setEditing(true)}>
              Edit terms
            </button>
          )}
        </>
      )}
      {editing && (
        <form onSubmit={submit} className="stack">
          <label className="field">
            Terms URL
            <input name="termsUrl" type="url" defaultValue={source.termsUrl ?? ''} />
          </label>
          <label className="field">
            Summary
            <textarea name="termsSummary" rows={3} defaultValue={source.termsSummary ?? ''} />
          </label>
          <label className="field">
            Reason for the change
            <textarea name="notes" rows={2} required />
          </label>
          <div className="actions">
            <button type="submit">Save terms</button>
            <button type="button" className="secondary" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
          <ErrorText error={error} />
        </form>
      )}
    </Section>
  )
}

function RightsSection({ source, canEdit, onChanged }: { source: SourceView; canEdit: boolean; onChanged: () => void }) {
  const [editing, setEditing] = useState<ContentUse | null>(null)

  return (
    <Section title="Rights per content use">
      <p className="hint">
        A use is allowed only when it is permitted, in date, not suspended and the publisher is active (FR-005).
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Use</th>
              <th scope="col">Recorded</th>
              <th scope="col">Valid</th>
              <th scope="col">Allowed now</th>
              {canEdit && <th scope="col">Actions</th>}
            </tr>
          </thead>
          <tbody>
            {source.rights.map((r) => (
              <tr key={r.use}>
                <th scope="row">{r.use.toLowerCase().replaceAll('_', ' ')}</th>
                <td>
                  <Badge value={r.status ?? 'UNKNOWN'} />
                  {r.suspended && (
                    <div>
                      <Badge value="SUSPENDED" /> <span className="hint">{r.suspendedReason}</span>
                    </div>
                  )}
                  {r.conditions && <div className="hint">{r.conditions}</div>}
                </td>
                <td>
                  {r.validFrom || r.expiresAt ? (
                    <>
                      <Time value={r.validFrom} /> → <Time value={r.expiresAt} />
                    </>
                  ) : (
                    <span className="hint">no dates</span>
                  )}
                </td>
                <td>
                  {r.allowed ? <Badge value="PERMITTED" /> : <span className="hint">{r.reason.toLowerCase().replaceAll('_', ' ')}</span>}
                </td>
                {canEdit && (
                  <td>
                    <div className="actions">
                      <button type="button" className="secondary small" onClick={() => setEditing(r.use)}>
                        Record decision
                      </button>
                      {r.status !== null && !r.suspended && (
                        <NotesAction
                          label="Suspend use"
                          danger
                          onSubmit={(notes) =>
                            api.post(`/admin/sources/${source.id}/rights/${r.use}/suspend`, { notes }).then(onChanged)
                          }
                        />
                      )}
                      {r.suspended && (
                        <NotesAction
                          label="Restore use"
                          onSubmit={(notes) =>
                            api.post(`/admin/sources/${source.id}/rights/${r.use}/restore`, { notes }).then(onChanged)
                          }
                        />
                      )}
                    </div>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing && (
        <RightsForm
          sourceId={source.id}
          rights={source.rights.find((r) => r.use === editing)!}
          onDone={() => {
            setEditing(null)
            onChanged()
          }}
          onCancel={() => setEditing(null)}
        />
      )}
    </Section>
  )
}

function RightsForm({
  sourceId,
  rights,
  onDone,
  onCancel,
}: {
  sourceId: number
  rights: RightsView
  onDone: () => void
  onCancel: () => void
}) {
  const [error, setError] = useState<unknown>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    try {
      await api.put(`/admin/sources/${sourceId}/rights/${rights.use}`, {
        status: String(f.get('status')),
        validFrom: localToIso(f.get('validFrom')),
        expiresAt: localToIso(f.get('expiresAt')),
        conditions: String(f.get('conditions') ?? '').trim() || null,
        notes: String(f.get('notes') ?? '').trim(),
        evidenceUrl: String(f.get('evidenceUrl') ?? '').trim() || null,
        nextReviewDue: String(f.get('nextReviewDue') ?? '') || null,
      })
      onDone()
    } catch (err) {
      setError(err)
    }
  }

  return (
    <form onSubmit={submit} className="stack panel" aria-label={`Rights decision for ${rights.use}`}>
      <h3>Record decision: {rights.use.toLowerCase().replaceAll('_', ' ')}</h3>
      <label className="field">
        Decision
        <select name="status" defaultValue={rights.status ?? 'PERMITTED'}>
          <option value="PERMITTED">Permitted</option>
          <option value="DENIED">Denied</option>
          <option value="DISPUTED">Disputed</option>
          <option value="UNKNOWN">Unknown (blocks the use)</option>
        </select>
      </label>
      <label className="field">
        Valid from (optional)
        <input name="validFrom" type="datetime-local" />
      </label>
      <label className="field">
        Expires (optional)
        <input name="expiresAt" type="datetime-local" />
      </label>
      <label className="field">
        Conditions (optional)
        <textarea name="conditions" rows={2} defaultValue={rights.conditions ?? ''} />
      </label>
      <label className="field">
        Evidence URL (optional)
        <input name="evidenceUrl" type="url" />
      </label>
      <label className="field">
        Next review due (optional)
        <input name="nextReviewDue" type="date" />
      </label>
      <label className="field">
        Notes (required: where the permission comes from)
        <textarea name="notes" rows={3} required />
      </label>
      <p className="hint">A new decision does not lift a suspension; use “Restore use” for that.</p>
      <div className="actions">
        <button type="submit">Save decision</button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
      <ErrorText error={error} />
    </form>
  )
}

function FeedsSection({ source, canEdit, onChanged }: { source: SourceView; canEdit: boolean; onChanged: () => void }) {
  const [error, setError] = useState<unknown>(null)

  const add = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    const f = new FormData(form)
    try {
      await api.post(`/admin/sources/${source.id}/feeds`, {
        feedUrl: String(f.get('feedUrl') ?? '').trim(),
        refreshIntervalMinutes: Number(f.get('interval') ?? 30),
      })
      form.reset()
      setError(null)
      onChanged()
    } catch (err) {
      setError(err)
    }
  }

  return (
    <Section title="Feeds">
      {source.feeds.length === 0 && <p className="hint">No feeds yet.</p>}
      {source.feeds.map((f) => (
        <FeedCard key={f.id} feed={f} canEdit={canEdit} onChanged={onChanged} />
      ))}
      {canEdit && (
        <form onSubmit={add} className="inline-form">
          <label className="field grow">
            New feed URL
            <input name="feedUrl" type="url" required />
          </label>
          <label className="field">
            Refresh (minutes)
            <input name="interval" type="number" min={5} max={1440} defaultValue={30} />
          </label>
          <button type="submit">Add feed</button>
        </form>
      )}
      <ErrorText error={error} />
    </Section>
  )
}

function FeedCard({ feed, canEdit, onChanged }: { feed: FeedView; canEdit: boolean; onChanged: () => void }) {
  const [jobs, setJobs] = useState<JobView[] | null>(null)
  const [quarantine, setQuarantine] = useState<{ jobId: number; items: QuarantineView[] } | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const loadJobs = async () => setJobs(await api.get<JobView[]>(`/admin/feeds/${feed.id}/jobs?limit=20`))

  const act = async (fn: () => Promise<unknown>) => {
    try {
      await fn()
      setMessage(null)
      onChanged()
      if (jobs) await loadJobs()
    } catch (err) {
      setMessage(errorText(err))
    }
  }

  return (
    <div className="panel">
      <p>
        <Badge value={feed.health} /> <span className="url">{feed.feedUrl}</span>
      </p>
      <p className="hint">
        Every {feed.refreshIntervalMinutes} min · last outcome {feed.lastOutcome ?? '—'} · last attempt{' '}
        <Time value={feed.lastAttemptAt} /> · last success <Time value={feed.lastSuccessAt} /> · next run{' '}
        <Time value={feed.nextRunAt} />
      </p>
      <div className="actions">
        {canEdit && (
          <>
            <button
              type="button"
              onClick={() =>
                act(async () => {
                  const job = await api.post<JobView>(`/admin/feeds/${feed.id}/run`)
                  setMessage(
                    `Run finished: ${job.outcome} (new ${job.itemsCreated}, updated ${job.itemsUpdated}, quarantined ${job.itemsQuarantined})`,
                  )
                })
              }
            >
              Run now
            </button>
            <button
              type="button"
              className="secondary"
              onClick={() => act(() => api.patch(`/admin/feeds/${feed.id}`, { enabled: !feed.enabled }))}
            >
              {feed.enabled ? 'Disable' : 'Enable'}
            </button>
          </>
        )}
        <button type="button" className="secondary" onClick={() => void loadJobs()}>
          Show recent runs
        </button>
      </div>
      {message && <p role="status">{message}</p>}
      {jobs && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Started</th>
                <th scope="col">Outcome</th>
                <th scope="col">Items</th>
                <th scope="col">Details</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => (
                <tr key={j.id}>
                  <td>
                    <Time value={j.startedAt} />
                  </td>
                  <td>
                    <Badge value={j.outcome} /> {j.httpStatus && <span className="hint">HTTP {j.httpStatus}</span>}
                  </td>
                  <td>
                    seen {j.itemsSeen}, new {j.itemsCreated}, updated {j.itemsUpdated}
                    {j.itemsQuarantined > 0 && (
                      <>
                        ,{' '}
                        <button
                          type="button"
                          className="secondary small"
                          onClick={async () =>
                            setQuarantine({
                              jobId: j.id,
                              items: await api.get<QuarantineView[]>(`/admin/jobs/${j.id}/quarantine`),
                            })
                          }
                        >
                          {j.itemsQuarantined} quarantined
                        </button>
                      </>
                    )}
                  </td>
                  <td>{j.diagnostic}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {quarantine && (
        <div className="panel">
          <h3>Quarantined entries (run {quarantine.jobId})</h3>
          <ul>
            {quarantine.items.map((q, i) => (
              <li key={i}>
                <Badge value={q.reason} /> {q.diagnostic} <span className="hint url">{q.entryLink ?? q.entryGuid}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
