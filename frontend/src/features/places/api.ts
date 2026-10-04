import { request } from '../../shared/api.ts'
import type { DateCourse, Place, Places } from '../../shared/places/types.ts'

export type Where = { lat: number; lng: number } | { near: string }

export function fetchPlaces(food: string, where: Where): Promise<Places> {
  const params = new URLSearchParams(
    'near' in where ? { food, near: where.near } : { food, lat: String(where.lat), lng: String(where.lng) },
  )
  return request(`/api/places?${params}`)
}

// the server answers the course without its restaurant; the place on screen fills it in
export async function fetchCourse(place: Place): Promise<DateCourse> {
  const params = new URLSearchParams({ id: place.id, name: place.name, lat: String(place.lat), lng: String(place.lng) })
  const course = await request<Omit<DateCourse, 'restaurant' | 'picked'>>(`/api/places/course?${params}`)
  return { restaurant: place, ...course, picked: true }
}
