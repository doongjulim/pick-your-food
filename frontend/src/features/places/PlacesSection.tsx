import { Crosshair, MapPin } from '@phosphor-icons/react'
import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { HttpError } from '../../shared/api.ts'
import { fetchPlaces } from './api.ts'
import type { Places, Where } from './api.ts'
import DateCourses from './DateCourses.tsx'
import PlaceTable from './PlaceTable.tsx'

type State = { status: 'idle' | 'locating' | 'loading' | 'error' } | { status: 'done'; data: Places }

export default function PlacesSection({ food }: { food: string }) {
  const [state, setState] = useState<State>({ status: 'idle' })
  const [notice, setNotice] = useState('')
  const [query, setQuery] = useState('')
  const input = useRef<HTMLInputElement>(null)
  // a slower, older search (or location request) must not overwrite a newer one
  const latest = useRef(0)
  const lastWhere = useRef<Where | null>(null)

  function search(where: Where) {
    const id = ++latest.current
    lastWhere.current = where
    setNotice('')
    setState({ status: 'loading' })
    fetchPlaces(food, where).then(
      (data) => id === latest.current && setState({ status: 'done', data }),
      (error: unknown) => {
        if (id !== latest.current) return
        if (error instanceof HttpError && error.status === 404 && 'near' in where) {
          setNotice(`'${where.near}'을 찾지 못했어요.`)
          setState({ status: 'idle' })
        } else {
          setState({ status: 'error' })
        }
      },
    )
  }

  function denied() {
    setState({ status: 'idle' })
    setNotice('위치 권한이 없어요. 동네 이름으로 찾아보세요.')
    input.current?.focus()
  }

  function locate() {
    const id = ++latest.current
    setNotice('')
    if (!navigator.geolocation) {
      denied()
      return
    }
    setState({ status: 'locating' })
    navigator.geolocation.getCurrentPosition(
      (position) => id === latest.current && search({ lat: position.coords.latitude, lng: position.coords.longitude }),
      () => id === latest.current && denied(),
      { timeout: 10_000 },
    )
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const near = query.trim()
    if (near) search({ near })
  }

  return (
    <section className="mt-20 border-t border-zinc-200 pt-12">
      <div className="grid gap-8 md:grid-cols-[1fr_1.2fr] md:items-end">
        <h2 className="text-3xl font-semibold tracking-tight md:text-4xl">이 메뉴, 어디서 먹지?</h2>
        <form onSubmit={submit} className="grid gap-2">
          <label htmlFor="near" className="text-sm text-zinc-500">
            동네나 역 이름
          </label>
          <div className="flex flex-wrap gap-2">
            <input
              id="near"
              ref={input}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="성수동, 강남역"
              className="min-w-0 flex-1 basis-40 rounded-full border border-zinc-300 bg-white px-5 py-3 text-sm outline-none transition focus:border-accent"
            />
            <button
              type="submit"
              disabled={!query.trim()}
              className="rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-40"
            >
              찾기
            </button>
            <button
              type="button"
              onClick={locate}
              disabled={state.status === 'locating'}
              className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
            >
              <Crosshair size={16} /> {state.status === 'locating' ? '위치 확인 중' : '현재 위치로'}
            </button>
          </div>
          {notice && (
            <p role="alert" className="text-sm text-accent">
              {notice}
            </p>
          )}
        </form>
      </div>

      <div className="mt-12">
        {(state.status === 'idle' || state.status === 'locating') && (
          <p className="text-base text-zinc-500">위치를 정하면 주변 맛집과 데이트 코스를 보여드릴게요.</p>
        )}
        {state.status === 'loading' && <PlacesSkeleton />}
        {state.status === 'error' && (
          <div className="rounded-3xl border border-zinc-200 bg-white p-8">
            <p className="text-lg font-semibold">주변 맛집을 불러오지 못했어요.</p>
            <p className="mt-2 text-sm text-zinc-500">잠시 후 다시 시도해 주세요.</p>
            <button
              type="button"
              onClick={() => lastWhere.current && search(lastWhere.current)}
              className="mt-6 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
            >
              다시 시도
            </button>
          </div>
        )}
        {state.status === 'done' && (
          <>
            <p className="flex items-center gap-2 text-sm text-zinc-500">
              <MapPin size={16} className="text-accent" /> {state.data.origin.name} 기준
            </p>
            <div className="mt-6">
              <PlaceTable food={food} nearby={state.data.nearby} famous={state.data.famous} />
            </div>
            {state.data.dateCourses.length > 0 && (
              <div className="mt-16">
                <DateCourses courses={state.data.dateCourses} />
              </div>
            )}
          </>
        )}
      </div>
    </section>
  )
}

function PlacesSkeleton() {
  return (
    <div className="animate-pulse">
      <div className="h-10 w-56 rounded-full bg-zinc-200" />
      <div className="mt-6 divide-y divide-zinc-200">
        {[0, 1, 2, 3, 4].map((row) => (
          <div key={row} className="flex items-center justify-between py-5">
            <div className="h-5 w-40 rounded-full bg-zinc-200" />
            <div className="h-4 w-24 rounded-full bg-zinc-200" />
          </div>
        ))}
      </div>
      <div className="mt-16 h-6 w-48 rounded-full bg-zinc-200" />
      <div className="mt-6 h-24 rounded-2xl bg-zinc-200" />
    </div>
  )
}
