import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import { I18nProvider } from '../i18n/I18nProvider'
import { mockFetch } from '../test/render'
import { AdminLayout } from './AdminLayout'
import { ReportAdminDetailPage } from './ReportsAdminPages'
import { SourceDetailPage } from './SourceDetailPage'
import { SourcesPage } from './SourcesPage'
import type { SourceView, StaffReport } from './types'

const staff = (roles: string[]) => ({ id: 1, email: 'staff@example.org', accountType: 'STAFF', roles })

function renderAdmin(path: string, element: ReactElement, routePath: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nProvider initial="en">
        <AuthProvider>
          <Routes>
            <Route path="/sign-in" element={<p>sign-in page</p>} />
            <Route path="/admin" element={<AdminLayout />}>
              <Route path={routePath} element={element} />
            </Route>
          </Routes>
        </AuthProvider>
      </I18nProvider>
    </MemoryRouter>,
  )
}

const source: SourceView = {
  id: 5,
  code: 'demo-en',
  name: 'Demo English Times',
  homepageUrl: 'https://en.demo.invalid/',
  primaryLanguage: 'en',
  status: 'PROPOSED',
  statusReason: null,
  termsUrl: null,
  termsSummary: null,
  rights: [
    { use: 'METADATA', status: null, validFrom: null, expiresAt: null, conditions: null, suspended: false, suspendedReason: null, allowed: false, reason: 'SOURCE_NOT_ACTIVE' },
    { use: 'NARRATION', status: 'PERMITTED', validFrom: null, expiresAt: null, conditions: null, suspended: false, suspendedReason: null, allowed: true, reason: 'PERMITTED' },
  ],
  feeds: [],
}

describe('staff console', () => {
  it('sends readers to sign-in', async () => {
    mockFetch({
      '/api/v1/auth/me': { body: { id: 2, email: 'r@example.org', accountType: 'READER', roles: [] } },
      '/api/v1/me/preferences': { body: { uiLanguage: 'en', newsLanguages: [], topics: [], digestLanguage: null, historyPaused: false } },
    })
    renderAdmin('/admin/sources', <SourcesPage />, 'sources')
    expect(await screen.findByText('sign-in page')).toBeInTheDocument()
  })

  it('shows the add-publisher form only to source managers', async () => {
    mockFetch({ '/api/v1/auth/me': { body: staff(['ANALYST']) }, '/api/v1/admin/sources': { body: [source] } })
    const { unmount } = renderAdmin('/admin/sources', <SourcesPage />, 'sources')
    expect(await screen.findByRole('link', { name: 'Demo English Times' })).toBeInTheDocument()
    expect(screen.getByText('narration')).toBeInTheDocument()
    expect(screen.queryByText('Add a publisher')).not.toBeInTheDocument()
    unmount()

    mockFetch({ '/api/v1/auth/me': { body: staff(['SOURCE_MANAGER']) }, '/api/v1/admin/sources': { body: [source] } })
    renderAdmin('/admin/sources', <SourcesPage />, 'sources')
    expect(await screen.findByText('Add a publisher')).toBeInTheDocument()
  })

  it('records a rights decision with the required notes', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const calls = mockFetch({
      '/api/v1/auth/me': { body: staff(['SOURCE_MANAGER']) },
      '/api/v1/admin/sources/5': { body: { source, reviews: [] } },
      '/api/v1/admin/sources/5/rights/METADATA': { body: source },
    })
    renderAdmin('/admin/sources/5', <SourceDetailPage />, 'sources/:id')

    const buttons = await screen.findAllByRole('button', { name: 'Record decision' })
    await userEvent.click(buttons[0])
    await userEvent.type(screen.getByLabelText(/Notes \(required/), 'Licence clause 2 allows headlines.')
    await userEvent.click(screen.getByRole('button', { name: 'Save decision' }))

    const put = calls.find((c) => c.url === '/api/v1/admin/sources/5/rights/METADATA')
    expect(put?.init?.method).toBe('PUT')
    expect(JSON.parse(String(put?.init?.body))).toMatchObject({
      status: 'PERMITTED',
      notes: 'Licence clause 2 allows headlines.',
      expiresAt: null,
    })
  })

  it('only offers "resolved" for a report under review and sends the resolution', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const report: StaffReport = {
      id: 9, reference: 'NP-ABCDEFGH', type: 'BROKEN_LINK', status: 'UNDER_REVIEW', resolution: null, articleId: null,
      articleTitle: null, sourceCode: null, reporterType: 'READER', notifyMe: true, description: 'Link is broken here.',
      pageUrl: '/articles/1', uiLanguage: 'ur', assignedTo: 1, assignedToEmail: 'staff@example.org',
      createdAt: '2026-10-10T09:00:00Z', updatedAt: '2026-10-10T09:00:00Z', resolvedAt: null, history: [],
    }
    const calls = mockFetch({
      '/api/v1/auth/me': { body: staff(['CONTENT_REVIEWER']) },
      '/api/v1/admin/reports/9': { body: report },
      '/api/v1/admin/reports/9/status': { body: { ...report, status: 'RESOLVED' } },
    })
    renderAdmin('/admin/reports/9', <ReportAdminDetailPage />, 'reports/:id')

    const select = await screen.findByLabelText('New status')
    expect(Array.from((select as HTMLSelectElement).options).map((o) => o.value)).toEqual(['RESOLVED'])
    await userEvent.click(screen.getByRole('button', { name: 'Save status' }))

    const post = calls.find((c) => c.url === '/api/v1/admin/reports/9/status')
    expect(JSON.parse(String(post?.init?.body))).toMatchObject({ status: 'RESOLVED', resolution: 'FIXED' })
  })
})
