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

// distanceMeters is Kakao's distance from the course's restaurant; saved results have none
export type Spot = {
  id: string
  name: string
  category: string
  address: string
  lat: number
  lng: number
  kakaoUrl: string
  distanceMeters?: number
}

// walking distance between two stops; path is the walking route as [lat, lng] points,
// null (or missing in older saved results) when only the straight line is known
export type Leg = {
  from: string
  to: string
  meters: number
  walkMinutes: number
  path: [number, number][] | null
}

// legs join only the stops that exist; routeUrl is null when the restaurant is the only stop;
// picked is true for the course built from a restaurant the user chose (missing or null for the others);
// cafes and sights are the nearest candidates to swap to, only in a fresh search (saved results have none)
export type DateCourse = {
  restaurant: Place
  cafe: Spot | null
  sight: Spot | null
  legs: Leg[]
  routeUrl: string | null
  picked?: boolean | null
  cafes?: Spot[]
  sights?: Spot[]
}

export type Places = {
  origin: { name: string; lat: number; lng: number }
  nearby: Place[]
  famous: Place[]
  dateCourses: DateCourse[]
}
