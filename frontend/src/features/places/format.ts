import type { Place } from './api.ts'

export function distance(meters: number): string {
  return meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`
}

// one line for narrow screens, e.g. "₩₩ · ★4.3 · 리뷰 1,284 · 820m"; unknown parts are left out
export function summary(place: Place): string {
  return [
    place.priceLevel !== null && '₩'.repeat(place.priceLevel),
    place.rating !== null && `★${place.rating.toFixed(1)}`,
    place.reviewCount !== null && `리뷰 ${place.reviewCount.toLocaleString('ko-KR')}`,
    distance(place.distanceMeters),
  ]
    .filter(Boolean)
    .join(' · ')
}
