import { HttpError, request } from '../../shared/api.ts'

export type Me = {
  id: number
  nickname: string
}

// null when nobody is logged in
export async function fetchMe(): Promise<Me | null> {
  try {
    return await request<Me>('/api/me')
  } catch (error) {
    if (error instanceof HttpError && error.status === 401) return null
    throw error
  }
}

export function logout(): Promise<void> {
  return request('/api/logout', { method: 'POST' })
}
