import { useEffect, useRef, useState } from 'react'
import type { DateCourse } from './types.ts'
import { loadKakaoMaps } from './kakaoMap.ts'
import type { KakaoMap, KakaoMaps } from './kakaoMap.ts'

// the accent color, oklch(0.6 0.15 32), in a form the SDK takes
const ACCENT = '#ca5843'

// numbered like the timeline: the cafe is 02 and the sight 03 even when a step is missing
function pin(number: number): HTMLElement {
  const element = document.createElement('div')
  element.className =
    'flex size-7 items-center justify-center rounded-full bg-accent font-mono text-xs font-semibold text-white ring-2 ring-white shadow-[0_4px_12px_-4px_rgba(0,0,0,0.4)]'
  element.textContent = String(number).padStart(2, '0')
  return element
}

// the course on a Kakao map: numbered pins, solid lines along walking routes, dashed straight lines without one
export default function CourseMap({ course }: { course: DateCourse }) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<KakaoMap | null>(null)
  // undefined while loading, null when there is no map to show
  const [maps, setMaps] = useState<KakaoMaps | null>()

  useEffect(() => {
    let mounted = true
    loadKakaoMaps().then((loaded) => mounted && setMaps(loaded))
    return () => {
      mounted = false
    }
  }, [])

  useEffect(() => {
    if (!maps || !container.current) return
    const stops = [
      { number: 1, place: course.restaurant },
      { number: 2, place: course.cafe },
      { number: 3, place: course.sight },
    ].flatMap(({ number, place }) => (place ? [{ number, at: new maps.LatLng(place.lat, place.lng) }] : []))
    if (!map.current) {
      map.current = new maps.Map(container.current, { center: stops[0].at, level: 4, draggable: false, scrollwheel: false })
      map.current.addControl(new maps.ZoomControl(), maps.ControlPosition.RIGHT)
    }
    // legs join only the stops that exist, in order
    const lines = course.legs.map((leg, index) =>
      leg.path
        ? new maps.Polyline({
            path: leg.path.map(([lat, lng]) => new maps.LatLng(lat, lng)),
            strokeWeight: 4, strokeColor: ACCENT, strokeOpacity: 0.9, strokeStyle: 'solid',
          })
        : new maps.Polyline({
            path: [stops[index].at, stops[index + 1].at],
            strokeWeight: 3, strokeColor: ACCENT, strokeOpacity: 0.9, strokeStyle: 'shortdash',
          }),
    )
    const pins = stops.map(({ number, at }) => new maps.CustomOverlay({ position: at, content: pin(number), yAnchor: 0.5 }))
    const bounds = new maps.LatLngBounds()
    stops.forEach(({ at }) => bounds.extend(at))
    course.legs.forEach((leg) => leg.path?.forEach(([lat, lng]) => bounds.extend(new maps.LatLng(lat, lng))))
    const layers = [...lines, ...pins]
    layers.forEach((layer) => layer.setMap(map.current))
    map.current.setBounds(bounds)
    return () => layers.forEach((layer) => layer.setMap(null))
  }, [maps, course])

  if (maps === null) return null
  return (
    <div
      ref={container}
      aria-label="코스 지도"
      className={`mt-8 h-60 overflow-hidden rounded-3xl border border-zinc-200 bg-zinc-100 md:h-80 ${maps ? '' : 'animate-pulse'}`}
    />
  )
}
