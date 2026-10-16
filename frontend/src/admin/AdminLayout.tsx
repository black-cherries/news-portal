import { Navigate, NavLink, Outlet } from 'react-router'
import { useAuth } from '../auth/AuthProvider'
import { useRoles } from './hooks'

/**
 * Staff console shell. Only staff accounts get here; each page shows only
 * what the account's roles allow. The backend enforces the same rules.
 *
 * English-only and always left-to-right for now, whatever the interface language.
 */
export function AdminLayout() {
  const { me } = useAuth()
  const { has } = useRoles()

  if (me === undefined) return <p role="status">Loading…</p>
  if (!me || me.accountType !== 'STAFF') return <Navigate to="/sign-in" replace />

  return (
    <div lang="en" dir="ltr" className="admin">
      <nav aria-label="Admin" className="admin-nav">
        <ul className="nav-list">
          <li>
            <NavLink to="/admin" end>
              Dashboard
            </NavLink>
          </li>
          <li>
            <NavLink to="/admin/sources">Publishers</NavLink>
          </li>
          <li>
            <NavLink to="/admin/health">Source health</NavLink>
          </li>
          {(has('CONTENT_REVIEWER') || has('ANALYST')) && (
            <li>
              <NavLink to="/admin/reports">Reports</NavLink>
            </li>
          )}
          {has('CONTENT_REVIEWER') && (
            <li>
              <NavLink to="/admin/alerts">Alerts</NavLink>
            </li>
          )}
          {has('SYSTEM_ADMIN') && (
            <>
              <li>
                <NavLink to="/admin/staff">Staff</NavLink>
              </li>
              <li>
                <NavLink to="/admin/ops">Operations</NavLink>
              </li>
              <li>
                <NavLink to="/admin/audit">Audit log</NavLink>
              </li>
            </>
          )}
        </ul>
        <p className="hint">
          Signed in as {me.email} · roles: {me.roles.join(', ') || 'none'}
        </p>
      </nav>
      <Outlet />
    </div>
  )
}
