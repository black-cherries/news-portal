import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/render'
import { ApiError, api, query } from './client'

describe('api client', () => {
  it('fetches a CSRF cookie before the first unsafe request and sends it as a header', async () => {
    const calls = mockFetch({ '/api/v1/auth/csrf': { body: {} }, '/api/v1/me/bookmarks/5': { status: 204 } })
    const realFetch = globalThis.fetch
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/v1/auth/csrf') document.cookie = 'XSRF-TOKEN=abc123; path=/'
      return realFetch(input, init)
    }) as typeof fetch

    await api.put('/me/bookmarks/5')

    expect(calls.map((c) => c.url)).toEqual(['/api/v1/auth/csrf', '/api/v1/me/bookmarks/5'])
    const headers = (calls[1].init?.headers ?? {}) as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBe('abc123')
  })

  it('does not send the CSRF header on GET', async () => {
    const calls = mockFetch({ '/api/v1/articles': { body: { items: [], nextCursor: null } } })
    await api.get('/articles')
    const headers = (calls[0].init?.headers ?? {}) as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('turns error responses into ApiError with the server code', async () => {
    mockFetch({ '/api/v1/auth/login': { status: 401, body: { code: 'INVALID_CREDENTIALS' } } })
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const err = await api.post('/auth/login', {}).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
    expect((err as ApiError).code).toBe('INVALID_CREDENTIALS')
  })

  it('builds query strings without empty values', () => {
    expect(query({ q: 'x y', lang: ['en', 'ur'], source: '', page: 0, to: null })).toBe('?q=x+y&lang=en%2Cur&page=0')
    expect(query({})).toBe('')
  })
})
