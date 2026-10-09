import { HttpError, request } from '../../shared/api.ts'
import type { Food } from '../../shared/api.ts'
import type { DateCourse, Place, Places, Spot } from '../../shared/places/types.ts'

// what gets saved: the menu and, when searched, the places and date courses around it
export type Saved = {
  title: string
  best: Food
  alternatives: Food[]
  places: Places | null
}

export type SavedEntry = {
  id: string
  createdAt: string
  mine: boolean
  shared: boolean
  result: Saved
}

export type SavedSummary = {
  id: string
  title: string
  foodName: string
  originName: string | null
  createdAt: string
  shared: boolean
}

type KakaoPlace = Pick<Place, 'id' | 'name' | 'address' | 'distanceMeters' | 'lat' | 'lng' | 'kakaoUrl'>

type PlacesOf<P> = Omit<Places, 'nearby' | 'famous' | 'dateCourses'> & {
  nearby: P[]
  famous: P[]
  dateCourses: (Omit<DateCourse, 'restaurant'> & { restaurant: P })[]
}

function mapPlaces<A, B>(places: PlacesOf<A>, map: (place: A) => B): PlacesOf<B> {
  return {
    ...places,
    nearby: places.nearby.map(map),
    famous: places.famous.map(map),
    dateCourses: places.dateCourses.map((course) => ({ ...course, restaurant: map(course.restaurant) })),
  }
}

// Google's terms don't allow keeping its data, so only the Kakao part of a place is sent
function kakaoOnly({ id, name, address, distanceMeters, lat, lng, kakaoUrl }: Place): KakaoPlace {
  return { id, name, address, distanceMeters, lat, lng, kakaoUrl }
}

// candidates only serve swapping on the search screen, and the saved format keeps no distance for a spot
function savedCourse({ restaurant, cafe, sight, legs, routeUrl, picked }: DateCourse): DateCourse {
  return { restaurant, cafe: cafe && savedSpot(cafe), sight: sight && savedSpot(sight), legs, routeUrl, picked }
}

function savedSpot({ id, name, category, address, lat, lng, kakaoUrl }: Spot): Spot {
  return { id, name, category, address, lat, lng, kakaoUrl }
}

// the tables show the missing Google fields as unknown
function withoutGoogle(place: KakaoPlace): Place {
  return { ...place, rating: null, reviewCount: null, priceLevel: null, reviews: [], googleUrl: null }
}

export async function saveResult(saved: Saved): Promise<string> {
  const { id } = await request<{ id: string }>('/api/saved', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...saved,
      places: saved.places && mapPlaces({ ...saved.places, dateCourses: saved.places.dateCourses.map(savedCourse) }, kakaoOnly),
    }),
  })
  return id
}

export async function fetchSaved(id: string): Promise<SavedEntry> {
  const entry = await request<Omit<SavedEntry, 'result'> & { result: Omit<Saved, 'places'> & { places: PlacesOf<KakaoPlace> | null } }>(
    `/api/saved/${encodeURIComponent(id)}`,
  )
  const places = entry.result.places && mapPlaces(entry.result.places, withoutGoogle)
  return { ...entry, result: { ...entry.result, places } }
}

// null when nobody is logged in
export async function fetchMySaved(): Promise<SavedSummary[] | null> {
  try {
    return await request<SavedSummary[]>('/api/saved')
  } catch (error) {
    if (error instanceof HttpError && error.status === 401) return null
    throw error
  }
}

export function deleteSaved(id: string): Promise<void> {
  return request(`/api/saved/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

// the owner renames the result or turns its share link off and on
export function updateSaved(id: string, change: { title?: string; shared?: boolean }): Promise<void> {
  return request(`/api/saved/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(change),
  })
}

// "9월 26일"
export function savedDate(createdAt: string): string {
  return new Date(createdAt).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' })
}
