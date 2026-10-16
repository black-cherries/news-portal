import { useState, type FormEvent, type ReactNode } from 'react'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { STAFF_ROLES, type AuditEntry, type OpsStatus, type RunResult, type StaffRole, type StaffView } from './types'
import { Badge, ErrorText, Section, Time } from './shared'
import { errorText, useLoad, useRoles } from './hooks'

function AdminOnly({ children }: { children: ReactNode }) {
  const { has } = useRoles()
  return has('SYSTEM_ADMIN') ? <>{children}</> : <p>This page is for system administrators.</p>
}

/** Staff accounts: invite, roles, disable/enable. Admins never set or see passwords. */
export function StaffPage() {
  return (
    <>
      <h1>Staff</h1>
      <AdminOnly>
        <StaffList />
      </AdminOnly>
    </>
  )
}

function StaffList() {
  const { me } = useAuth()
  const { data, error, reload } = useLoad<StaffView[]>('/admin/staff')
  const [actionError, setActionError] = useState<unknown>(null)
  const [editing, setEditing] = useState<number | null>(null)

  const act = async (fn: () => Promise<unknown>) => {
    try {
      await fn()
      setActionError(null)
      setEditing(null)
      await reload()
    } catch (err) {
      setActionError(err)
    }
  }

  const invite = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    const f = new FormData(form)
    await act(async () => {
      await api.post('/admin/staff', { email: String(f.get('email') ?? '').trim(), roles: f.getAll('roles').map(String) })
      form.reset()
    })
  }

  return (
    <>
      <ErrorText error={error} />
      <ErrorText error={actionError} />
      {data && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Email</th>
                <th scope="col">Status</th>
                <th scope="col">Roles</th>
                <th scope="col">Last sign-in</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {data.map((s) => (
                <tr key={s.id}>
                  <td>
                    {s.email}
                    {s.id === me?.id && <span className="hint"> (you)</span>}
                  </td>
                  <td>
                    <Badge value={s.status} />
                  </td>
                  <td>
                    {editing === s.id ? (
                      <form
                        onSubmit={(e) => {
                          e.preventDefault()
                          const roles = new FormData(e.currentTarget).getAll('roles').map(String)
                          void act(() => api.put(`/admin/staff/${s.id}/roles`, { roles }))
                        }}
                      >
                        <RoleChecks selected={s.roles} />
                        <div className="actions">
                          <button type="submit" className="small">
                            Save roles
                          </button>
                          <button type="button" className="secondary small" onClick={() => setEditing(null)}>
                            Cancel
                          </button>
                        </div>
                      </form>
                    ) : (
                      s.roles.join(', ')
                    )}
                  </td>
                  <td>
                    <Time value={s.lastLoginAt} />
                  </td>
                  <td>
                    <div className="actions">
                      {editing !== s.id && (
                        <button type="button" className="secondary small" onClick={() => setEditing(s.id)}>
                          Edit roles
                        </button>
                      )}
                      {s.id !== me?.id && s.status !== 'DISABLED' && (
                        <button
                          type="button"
                          className="danger small"
                          onClick={() => {
                            if (window.confirm(`Disable ${s.email}? They are signed out at once.`)) {
                              void act(() => api.post(`/admin/staff/${s.id}/disable`))
                            }
                          }}
                        >
                          Disable
                        </button>
                      )}
                      {s.status === 'DISABLED' && (
                        <button type="button" className="secondary small" onClick={() => void act(() => api.post(`/admin/staff/${s.id}/enable`))}>
                          Enable
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Section title="Invite staff">
        <p className="hint">The person gets an email to choose their own password. The link is valid for 72 hours.</p>
        <form onSubmit={invite} className="stack">
          <label className="field">
            Email
            <input name="email" type="email" required />
          </label>
          <fieldset>
            <legend>Roles</legend>
            <RoleChecks selected={[]} />
          </fieldset>
          <button type="submit">Send invitation</button>
        </form>
      </Section>
    </>
  )
}

function RoleChecks({ selected }: { selected: StaffRole[] }) {
  return (
    <>
      {STAFF_ROLES.map((r) => (
        <label key={r} className="check">
          <input type="checkbox" name="roles" value={r} defaultChecked={selected.includes(r)} />
          {r.toLowerCase().replaceAll('_', ' ')}
        </label>
      ))}
    </>
  )
}

/** Backups, restore tests and alerts (docs/operations.md). */
export function OpsPage() {
  return (
    <>
      <h1>Operations</h1>
      <AdminOnly>
        <OpsPanel />
      </AdminOnly>
    </>
  )
}

function OpsPanel() {
  const { data, error, reload } = useLoad<OpsStatus>('/admin/ops')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const run = async (path: string, label: string) => {
    setBusy(true)
    setMessage(`${label} running…`)
    try {
      const result = await api.post<RunResult | Record<string, string>>(path)
      if ('success' in result) {
        setMessage(`${label}: ${result.success ? 'succeeded' : 'FAILED'}${result.detail ? ` — ${result.detail}` : ''}`)
      } else {
        const problems = Object.values(result)
        setMessage(problems.length === 0 ? 'Checks found no problems.' : `Checks found ${problems.length} problem(s).`)
      }
      await reload()
    } catch (err) {
      setMessage(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => void run('/admin/ops/backup', 'Backup')}>
          Back up now
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => void run('/admin/ops/restore-test', 'Restore test')}>
          Test restore now
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => void run('/admin/ops/check', 'Checks')}>
          Run checks now
        </button>
      </div>
      {message && <p role="status">{message}</p>}
      <ErrorText error={error} />
      {data && (
        <>
          <Section title="Open alerts">
            {data.openAlerts.length === 0 && <p>None.</p>}
            <ul className="plain-list">
              {data.openAlerts.map((a) => (
                <li key={a.key}>
                  <Badge value="OPEN" /> {a.message} <span className="hint">since <Time value={a.firstSeenAt} /></span>
                </li>
              ))}
            </ul>
            {data.recentlyResolvedAlerts.length > 0 && (
              <details>
                <summary>Recently resolved</summary>
                <ul>
                  {data.recentlyResolvedAlerts.map((a) => (
                    <li key={a.key}>
                      {a.message} <span className="hint">resolved <Time value={a.resolvedAt} /></span>
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </Section>
          <Section title="Backups">
            <p className="hint">
              Folder: <span className="url">{data.backupDir}</span> · free space:{' '}
              {data.backupDiskFreeMb === null ? 'unknown' : `${data.backupDiskFreeMb.toLocaleString('en')} MB`}
            </p>
            <RunsTable runs={data.backups} />
          </Section>
          <Section title="Restore tests">
            <RunsTable runs={data.restoreTests} />
          </Section>
        </>
      )}
    </>
  )
}

function RunsTable({ runs }: { runs: OpsStatus['backups'] }) {
  if (runs.length === 0) return <p>None yet.</p>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">Finished</th>
            <th scope="col">Outcome</th>
            <th scope="col">File</th>
            <th scope="col">Details</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((r, i) => (
            <tr key={i}>
              <td>
                <Time value={r.finishedAt} />
              </td>
              <td>
                <Badge value={r.outcome} />
              </td>
              <td className="url">
                {r.fileName ?? '—'}
                {r.sizeBytes !== null && <div className="hint">{Math.round(r.sizeBytes / 1024)} KB</div>}
              </td>
              <td>{r.detail}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** FR-076: sensitive staff and security actions, newest first. */
export function AuditPage() {
  return (
    <>
      <h1>Audit log</h1>
      <AdminOnly>
        <AuditList />
      </AdminOnly>
    </>
  )
}

function AuditList() {
  const [before, setBefore] = useState<number | null>(null)
  const { data, error } = useLoad<AuditEntry[]>(`/admin/audit?limit=50${before ? `&before=${before}` : ''}`)
  return (
    <>
      <ErrorText error={error} />
      {data && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">Action</th>
                <th scope="col">Target</th>
                <th scope="col">Detail</th>
              </tr>
            </thead>
            <tbody>
              {data.map((a) => (
                <tr key={a.id}>
                  <td>
                    <Time value={a.createdAt} />
                  </td>
                  <td>{a.actorEmail ?? <span className="hint">system</span>}</td>
                  <td>{a.action.toLowerCase().replaceAll('_', ' ')}</td>
                  <td>
                    {a.targetType} {a.targetId}
                  </td>
                  <td>{a.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="actions">
        {before !== null && (
          <button type="button" className="secondary" onClick={() => setBefore(null)}>
            Newest
          </button>
        )}
        {data && data.length === 50 && (
          <button type="button" className="secondary" onClick={() => setBefore(data[data.length - 1].id)}>
            Older
          </button>
        )}
      </div>
    </>
  )
}
