import { request } from '../../shared/api.ts'
import type { DateCourse, Place, Places, Spot } from '../../shared/places/types.ts'

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

type Stop = Pick<Spot, 'id' | 'name' | 'lat' | 'lng'>
export type Walk = Pick<DateCourse, 'legs' | 'routeUrl'>

// the stops in order (the restaurant, then the cafe and sight that exist), each parameter repeated per stop
export function fetchLegs(stops: Stop[]): Promise<Walk> {
  const params = new URLSearchParams()
  for (const stop of stops) {
    params.append('id', stop.id)
    params.append('name', stop.name)
    params.append('lat', String(stop.lat))
    params.append('lng', String(stop.lng))
  }
  return request(`/api/places/legs?${params}`)
}
