export type Food = {
  id: string
  name: string
  description: string
}

export class HttpError extends Error {
  status: number

  constructor(status: number) {
    super(`HTTP ${status}`)
    this.status = status
  }
}

// Spring Security hands out the CSRF token in this cookie and checks it on every non-GET request
function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : undefined
}

export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = csrfToken()
  if ((init.method ?? 'GET') !== 'GET' && token) headers.set('X-XSRF-TOKEN', token)
  const res = await fetch(url, { ...init, headers })
  if (!res.ok) throw new HttpError(res.status)
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}
