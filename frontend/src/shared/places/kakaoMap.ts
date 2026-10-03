import { request } from '../api.ts'

// just the parts of the Kakao Maps SDK the course map uses
type LatLng = object
type Bounds = { extend: (point: LatLng) => void }
type Layer = { setMap: (map: KakaoMap | null) => void }

export type KakaoMap = {
  setBounds: (bounds: Bounds) => void
  addControl: (control: object, position: number) => void
}

export type KakaoMaps = {
  load: (ready: () => void) => void
  Map: new (container: HTMLElement, options: { center: LatLng; level: number; draggable: boolean; scrollwheel: boolean }) => KakaoMap
  LatLng: new (lat: number, lng: number) => LatLng
  LatLngBounds: new () => Bounds
  Polyline: new (options: { path: LatLng[]; strokeWeight: number; strokeColor: string; strokeOpacity: number; strokeStyle: string }) => Layer
  CustomOverlay: new (options: { position: LatLng; content: HTMLElement; yAnchor: number }) => Layer
  ZoomControl: new () => object
  ControlPosition: { RIGHT: number }
}

declare global {
  interface Window {
    kakao?: { maps: KakaoMaps }
  }
}

let loading: Promise<KakaoMaps | null> | undefined

// loads the SDK once per page; null when the server has no key or loading fails, and the map stays hidden
export function loadKakaoMaps(): Promise<KakaoMaps | null> {
  loading ??= request<{ key: string | null }>('/api/places/map-key')
    .then(({ key }) =>
      key
        ? new Promise<KakaoMaps | null>((resolve, reject) => {
            const script = document.createElement('script')
            script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`
            script.onload = () => {
              const maps = window.kakao?.maps
              if (maps) maps.load(() => resolve(maps))
              else resolve(null)
            }
            script.onerror = reject
            document.head.append(script)
          })
        : null,
    )
    .catch(() => null)
  return loading
}
