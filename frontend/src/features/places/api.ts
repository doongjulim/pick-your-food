import { request } from '../../shared/api.ts'

export type Review = {
  author: string | null
  rating: number | null
  text: string
  when: string | null
}

// rating, reviewCount, priceLevel and googleUrl are null when Google has no match
export type Place = {
  id: string
  name: string
  address: string
  distanceMeters: number
  lat: number
  lng: number
  kakaoUrl: string
  rating: number | null
  reviewCount: number | null
  priceLevel: number | null
  reviews: Review[]
  googleUrl: string | null
}

export type Spot = {
  name: string
  category: string
  address: string
  distanceMeters: number
  kakaoUrl: string
}

export type DateCourse = {
  restaurant: Place
  cafe: Spot | null
  sight: Spot | null
}

export type Places = {
  origin: { name: string; lat: number; lng: number }
  nearby: Place[]
  famous: Place[]
  dateCourse: DateCourse | null
}

export type Where = { lat: number; lng: number } | { near: string }

export function fetchPlaces(food: string, where: Where): Promise<Places> {
  const params = new URLSearchParams(
    'near' in where ? { food, near: where.near } : { food, lat: String(where.lat), lng: String(where.lng) },
  )
  return request(`/api/places?${params}`)
}
