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

export async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) throw new HttpError(res.status)
  return res.json() as Promise<T>
}
