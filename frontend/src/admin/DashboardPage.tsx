import { Link } from 'react-router'
import type { FeedHealthRow, OpsStatus } from './types'
import { Badge, ErrorText, Section, Time } from './shared'
import { useLoad, useRoles } from './hooks'

interface ReportStats {
  byStatus: { status: string; count: number }[]
  byType: { type: string; count: number }[]
  openOlderThan7Days: number
}

/** First screen: what needs attention, filtered to what this account may see. */
export function DashboardPage() {
  const { has } = useRoles()
  const health = useLoad<FeedHealthRow[]>('/admin/source-health')
  const ops = useLoad<OpsStatus>(has('SYSTEM_ADMIN') ? '/admin/ops' : null)
  const reports = useLoad<ReportStats>(has('CONTENT_REVIEWER') || has('ANALYST') ? '/admin/reports/stats' : null)

  const problems = (health.data ?? []).filter((r) => ['FAILING', 'STALE', 'RECOVERING'].includes(r.health))

  return (
    <>
      <h1>Dashboard</h1>

      <Section title="Feeds needing attention">
        <ErrorText error={health.error} />
        {health.data && problems.length === 0 && <p>All active feeds are healthy, pending or intentionally disabled.</p>}
        {problems.length > 0 && (
          <ul className="plain-list">
            {problems.map((r) => (
              <li key={r.feedId}>
                <Badge value={r.health} /> <Link to={`/admin/sources/${r.sourceId}`}>{r.sourceName}</Link> —{' '}
                {r.consecutiveFailures} failure(s) in a row, last success <Time value={r.lastSuccessAt} />
              </li>
            ))}
          </ul>
        )}
        <p>
          <Link to="/admin/health">All feeds</Link>
        </p>
      </Section>

      {has('SYSTEM_ADMIN') && (
        <Section title="Operations">
          <ErrorText error={ops.error} />
          {ops.data && (
            <>
              {ops.data.openAlerts.length === 0 ? (
                <p>No open alerts.</p>
              ) : (
                <ul className="plain-list">
                  {ops.data.openAlerts.map((a) => (
                    <li key={a.key}>
                      <Badge value="OPEN" /> {a.message}
                    </li>
                  ))}
                </ul>
              )}
              <p>
                Last backup: <Badge value={ops.data.backups[0]?.outcome ?? 'NONE'} />{' '}
                <Time value={ops.data.backups[0]?.finishedAt} /> · Last restore test:{' '}
                <Badge value={ops.data.restoreTests[0]?.outcome ?? 'NONE'} />{' '}
                <Time value={ops.data.restoreTests[0]?.finishedAt} /> · <Link to="/admin/ops">Details</Link>
              </p>
            </>
          )}
        </Section>
      )}

      {(has('CONTENT_REVIEWER') || has('ANALYST')) && (
        <Section title="Reports">
          <ErrorText error={reports.error} />
          {reports.data && (
            <p>
              {reports.data.byStatus.map((s) => (
                <span key={s.status} className="stat">
                  <Badge value={s.status} /> {s.count}
                </span>
              ))}
              {reports.data.byStatus.length === 0 && 'No reports yet. '}
              {reports.data.openOlderThan7Days > 0 && (
                <strong> {reports.data.openOlderThan7Days} open for more than 7 days.</strong>
              )}{' '}
              {has('CONTENT_REVIEWER') && <Link to="/admin/reports">Handle reports</Link>}
            </p>
          )}
        </Section>
      )}
    </>
  )
}
