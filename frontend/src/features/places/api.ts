import { request } from '../../shared/api.ts'
import type { Places } from '../../shared/places/types.ts'

export type Where = { lat: number; lng: number } | { near: string }

export function fetchPlaces(food: string, where: Where): Promise<Places> {
  const params = new URLSearchParams(
    'near' in where ? { food, near: where.near } : { food, lat: String(where.lat), lng: String(where.lng) },
  )
  return request(`/api/places?${params}`)
}
