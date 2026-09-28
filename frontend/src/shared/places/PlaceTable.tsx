import { ArrowDown, ArrowUp } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { Place } from './types.ts'
import { distance, summary } from './format.ts'
import MapLink from './MapLink.tsx'

type SortKey = 'distance' | 'price' | 'rating' | 'reviews'
type Sort = { key: SortKey; asc: boolean }

const TABS = [
  { key: 'nearby', label: '주위 1km', radius: '1km', sort: { key: 'distance', asc: true } },
  { key: 'famous', label: '근교 유명 맛집', radius: '20km', sort: { key: 'reviews', asc: false } },
] as const satisfies readonly { key: string; label: string; radius: string; sort: Sort }[]

type Tab = (typeof TABS)[number]

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'distance', label: '이름·거리' },
  { key: 'price', label: '가격대' },
  { key: 'rating', label: '평점' },
  { key: 'reviews', label: '리뷰 수' },
]

const GRID = 'md:grid-cols-[1fr_6rem_5rem_6rem]'

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

function value(place: Place, key: SortKey): number | null {
  switch (key) {
    case 'distance':
      return place.distanceMeters
    case 'price':
      return place.priceLevel
    case 'rating':
      return place.rating
    case 'reviews':
      return place.reviewCount
  }
}

// unknown values stay last whichever way the column is sorted
function sortPlaces(places: Place[], sort: Sort): Place[] {
  return [...places].sort((a, b) => {
    const x = value(a, sort.key)
    const y = value(b, sort.key)
    if (x === null || y === null) return x === y ? 0 : x === null ? 1 : -1
    return sort.asc ? x - y : y - x
  })
}

type Props = {
  food: string
  nearby: Place[]
  famous: Place[]
}

export default function PlaceTable({ food, nearby, famous }: Props) {
  const [tab, setTab] = useState<Tab>(TABS[0])
  const [sort, setSort] = useState<Sort>(TABS[0].sort)
  const [open, setOpen] = useState<string | null>(null)
  const places = sortPlaces(tab.key === 'nearby' ? nearby : famous, sort)

  function switchTab(next: Tab) {
    setTab(next)
    setSort(next.sort)
    setOpen(null)
  }

  function sortBy(key: SortKey) {
    // a new column starts with its natural direction: closest and cheapest first, best-rated and most-reviewed first
    setSort((prev) => (prev.key === key ? { key, asc: !prev.asc } : { key, asc: key === 'distance' || key === 'price' }))
  }

  return (
    <div>
      <div className="flex w-fit gap-1 rounded-full border border-zinc-200 bg-white p-1">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => switchTab(t)}
            className="relative rounded-full px-4 py-2 text-sm font-medium transition active:scale-[0.98]"
          >
            {tab.key === t.key && (
              <motion.span layoutId="places-tab" className="absolute inset-0 rounded-full bg-zinc-900" transition={spring} />
            )}
            <span className={`relative ${tab.key === t.key ? 'text-white' : 'text-zinc-600'}`}>{t.label}</span>
          </button>
        ))}
      </div>

      {places.length === 0 ? (
        <p className="mt-8 max-w-[50ch] text-base leading-relaxed text-zinc-500">
          {tab.radius} 안에서 {food} 파는 곳을 못 찾았어요.{' '}
          {tab.key === 'nearby' ? '근교 탭을 보거나 다른 동네로 찾아보세요.' : '다른 동네로 찾아보세요.'}
        </p>
      ) : (
        <div className="mt-6">
          <div className={`hidden border-b border-zinc-200 pb-3 text-sm text-zinc-500 md:grid ${GRID}`}>
            {COLUMNS.map((column, index) => (
              <button
                key={column.key}
                type="button"
                onClick={() => sortBy(column.key)}
                className={`flex items-center gap-1 transition hover:text-zinc-900 ${index === 0 ? '' : 'justify-end'} ${
                  sort.key === column.key ? 'text-zinc-900' : ''
                }`}
              >
                {column.label}
                {sort.key === column.key && (sort.asc ? <ArrowUp size={12} /> : <ArrowDown size={12} />)}
              </button>
            ))}
          </div>
          <ul className="divide-y divide-zinc-200 border-b border-zinc-200">
            {places.map((place) => (
              <li key={place.id}>
                <PlaceRow
                  place={place}
                  open={open === place.id}
                  onToggle={() => setOpen(open === place.id ? null : place.id)}
                />
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

type PlaceRowProps = {
  place: Place
  open: boolean
  onToggle: () => void
}

function PlaceRow({ place, open, onToggle }: PlaceRowProps) {
  return (
    <>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className={`grid w-full gap-1 py-4 text-left transition hover:bg-zinc-100/60 md:items-center ${GRID}`}
      >
        <span>
          <span className="block text-lg font-semibold tracking-tight">{place.name}</span>
          <span className="hidden font-mono text-sm text-zinc-500 md:block">{distance(place.distanceMeters)}</span>
        </span>
        <span className="font-mono text-sm text-zinc-500 md:hidden">{summary(place)}</span>
        <span className="hidden text-right md:block">
          <Price level={place.priceLevel} />
        </span>
        <span className="hidden text-right font-mono md:block">{place.rating?.toFixed(1) ?? <Unknown />}</span>
        <span className="hidden text-right font-mono md:block">
          {place.reviewCount?.toLocaleString('ko-KR') ?? <Unknown />}
        </span>
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={spring}
            className="pb-6"
          >
            <Reviews place={place} />
          </motion.div>
        )}
      </AnimatePresence>
    </>
  )
}

function Reviews({ place }: { place: Place }) {
  return (
    <div className="grid gap-6 rounded-2xl bg-white p-5 md:grid-cols-[1fr_auto] md:gap-10">
      {place.reviews.length === 0 ? (
        <p className="text-sm text-zinc-500">리뷰 정보가 없어요.</p>
      ) : (
        <ul className="grid gap-4">
          {place.reviews.map((review, index) => (
            <li key={index}>
              <p className="line-clamp-3 text-sm leading-relaxed text-zinc-700">{review.text}</p>
              <p className="mt-1 font-mono text-xs text-zinc-400">
                {[review.rating !== null && `★${review.rating}`, review.author ?? '익명', review.when]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap gap-2 md:flex-col">
        <MapLink href={place.kakaoUrl} label="카카오맵" />
        {place.googleUrl && <MapLink href={place.googleUrl} label="Google 지도" />}
      </div>
    </div>
  )
}

function Unknown() {
  return <span className="text-zinc-300">—</span>
}

function Price({ level }: { level: number | null }) {
  if (level === null) return <Unknown />
  return (
    <span className="font-mono">
      {'₩'.repeat(level)}
      <span className="text-zinc-300">{'₩'.repeat(4 - level)}</span>
    </span>
  )
}
