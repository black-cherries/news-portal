import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch, renderAt } from '../test/render'
import { ReportForm } from './ReportForm'

describe('ReportForm', () => {
  it('lets a guest send a report and shows the reference', async () => {
    const calls = mockFetch({
      '/api/v1/auth/me': { status: 401, body: {} },
      '/api/v1/reports': { status: 201, body: { reference: 'NP-ABCD2345', status: 'RECEIVED' } },
    })
    document.cookie = 'XSRF-TOKEN=t; path=/'
    renderAt(<ReportForm articleId={42} />, { lang: 'en' })

    expect(await screen.findByText(/Corrections are checked with the publisher/)).toBeInTheDocument()
    // Guests cannot ask for email updates.
    expect(screen.queryByLabelText('Email me when the status changes')).not.toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Describe the problem'), 'The date is a day early.')
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }))

    expect(await screen.findByText(/Your reference is NP-ABCD2345/)).toBeInTheDocument()
    const sent = calls.find((c) => c.url === '/api/v1/reports')
    const body = JSON.parse(String(sent?.init?.body))
    expect(body).toMatchObject({ type: 'CORRECTION_REQUEST', articleId: 42, description: 'The date is a day early.' })
    const headers = (sent?.init?.headers ?? {}) as Record<string, string>
    expect(headers['X-UI-Language']).toBe('en')
  })
})
