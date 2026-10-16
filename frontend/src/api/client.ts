// Small fetch wrapper for the /api/v1 backend.
//
// Sessions use an HttpOnly cookie. Unsafe requests (POST/PUT/PATCH/DELETE)
// must send the XSRF-TOKEN cookie value in the X-XSRF-TOKEN header; if the
// cookie is missing we ask the backend for one first.

export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly body: Record<string, unknown>

  constructor(status: number, body: Record<string, unknown>) {
    super(typeof body.detail === 'string' ? body.detail : `HTTP ${status}`)
    this.status = status
    this.body = body
    this.code = typeof body.code === 'string' ? body.code : undefined
  }
}

const BASE = '/api/v1'
const SAFE = new Set(['GET', 'HEAD', 'OPTIONS'])

function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`))
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null
}

async function csrfToken(): Promise<string | null> {
  let token = readCookie('XSRF-TOKEN')
  if (!token) {
    await fetch(`${BASE}/auth/csrf`, { credentials: 'same-origin' })
    token = readCookie('XSRF-TOKEN')
  }
  return token
}

export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (!SAFE.has(method)) {
    const token = await csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 204) return undefined as T
  const text = await response.text()
  const data = text ? (JSON.parse(text) as unknown) : undefined
  if (!response.ok) {
    throw new ApiError(response.status, (data ?? {}) as Record<string, unknown>)
  }
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body ?? {}),
  delete: <T>(path: string) => request<T>('DELETE', path),
}

/** Builds a query string, skipping empty values. */
export function query(params: Record<string, string | number | string[] | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      if (value.length > 0) search.set(key, value.join(','))
    } else {
      search.set(key, String(value))
    }
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}
