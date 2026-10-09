import { useEffect, useRef, useState } from 'react'
import type { Place } from './types.ts'
import { loadKakaoMaps } from './kakaoMap.ts'
import type { KakaoMap, KakaoMaps } from './kakaoMap.ts'

type Props = {
  places: Place[]
  origin: { lat: number; lng: number }
  open: string | null
  onPick: (id: string) => void
}

// a grey dot per restaurant; the open row's dot grows in the accent color with its name beside it
function pin(place: Place, open: boolean, onPick: (id: string) => void): HTMLElement {
  const button = document.createElement('button')
  button.type = 'button'
  button.setAttribute('aria-label', place.name)
  button.setAttribute('aria-pressed', String(open))
  button.className = 'relative block p-1.5'
  const dot = document.createElement('span')
  dot.className = open
    ? 'block size-4 rounded-full bg-accent ring-2 ring-white shadow-[0_4px_12px_-4px_rgba(0,0,0,0.4)]'
    : 'block size-3 rounded-full bg-zinc-500 ring-2 ring-white'
  button.append(dot)
  if (open) {
    // positioned off to the side so the dot, not the label, sits on the place
    const label = document.createElement('span')
    label.className =
      'absolute left-full top-1/2 max-w-40 -translate-y-1/2 truncate rounded-full bg-white px-2 py-0.5 text-xs font-medium text-zinc-900 shadow-[0_4px_12px_-4px_rgba(0,0,0,0.3)]'
    label.textContent = place.name
    button.append(label)
  }
  button.addEventListener('click', () => onPick(place.id))
  return button
}

// where the search was made from
function here(): HTMLElement {
  const element = document.createElement('div')
  element.className = 'size-3.5 rounded-full bg-zinc-900 ring-4 ring-white/80'
  return element
}

// the place table's current tab on a Kakao map; pins open their row and the open row's pin stands out
export default function PlaceMap({ places, origin, open, onPick }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<KakaoMap | null>(null)
  // undefined while loading, null when there is no map to show
  const [maps, setMaps] = useState<KakaoMaps | null>()
  // read inside the click handler instead of in the pins effect's deps, so a caller passing a
  // new onPick each render (PlacesSection does, via course={{ pick, ...picking }}) doesn't tear
  // down and rebuild every pin — that would drop focus from whichever pin the user just focused
  const onPickRef = useRef(onPick)
  useEffect(() => {
    onPickRef.current = onPick
  }, [onPick])

  useEffect(() => {
    let mounted = true
    loadKakaoMaps().then((loaded) => mounted && setMaps(loaded))
    return () => {
      mounted = false
    }
  }, [])

  // only a new set of pins (another tab) refits the map; opening a row or sorting leaves it where the user moved it
  useEffect(() => {
    if (!maps || !container.current) return
    const center = new maps.LatLng(origin.lat, origin.lng)
    if (!map.current) {
      map.current = new maps.Map(container.current, { center, level: 5, draggable: true, scrollwheel: false })
      map.current.addControl(new maps.ZoomControl(), maps.ControlPosition.RIGHT)
    }
    const bounds = new maps.LatLngBounds()
    bounds.extend(center)
    places.forEach((place) => bounds.extend(new maps.LatLng(place.lat, place.lng)))
    map.current.setBounds(bounds)
    const marker = new maps.CustomOverlay({ position: center, content: here(), yAnchor: 0.5 })
    marker.setMap(map.current)
    return () => marker.setMap(null)
  }, [maps, places, origin])

  useEffect(() => {
    if (!maps || !map.current) return
    const pins = places.map(
      (place) =>
        new maps.CustomOverlay({
          position: new maps.LatLng(place.lat, place.lng),
          content: pin(place, place.id === open, (id) => onPickRef.current(id)),
          yAnchor: 0.5,
          zIndex: place.id === open ? 1 : 0,
          clickable: true,
        }),
    )
    pins.forEach((overlay) => overlay.setMap(map.current))
    return () => pins.forEach((overlay) => overlay.setMap(null))
  }, [maps, places, open])

  if (maps === null) return null
  return (
    <div
      ref={container}
      aria-label="맛집 지도"
      className={`mt-6 h-60 overflow-hidden rounded-3xl border border-zinc-200 bg-zinc-100 md:h-80 ${maps ? '' : 'animate-pulse'}`}
    />
  )
}
