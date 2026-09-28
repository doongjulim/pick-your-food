import { CheckCircle, House, MapPin, Trash } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { HttpError } from '../../shared/api.ts'
import DateCourses from '../../shared/places/DateCourses.tsx'
import PlaceTable from '../../shared/places/PlaceTable.tsx'
import { deleteSaved, fetchSaved, savedDate } from './api.ts'
import type { SavedEntry } from './api.ts'
import ShareButton from './ShareButton.tsx'

type State = { status: 'loading' | 'missing' | 'error' } | { status: 'done'; entry: SavedEntry }

type Props = {
  id: string
  justSaved: boolean
  onHome: () => void
  onDeleted: () => void
}

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

const button =
  'flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40'

export default function SavedView({ id, justSaved, onHome, onDeleted }: Props) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [deleting, setDeleting] = useState<'idle' | 'busy' | 'failed'>('idle')

  useEffect(() => {
    let current = true
    fetchSaved(id).then(
      (entry) => current && setState({ status: 'done', entry }),
      (error: unknown) =>
        current && setState({ status: error instanceof HttpError && error.status === 404 ? 'missing' : 'error' }),
    )
    return () => {
      current = false
    }
  }, [id, attempt])

  function remove() {
    if (!confirm('이 결과를 지울까요? 링크도 더 이상 열리지 않아요.')) return
    setDeleting('busy')
    deleteSaved(id).then(onDeleted, () => setDeleting('failed'))
  }

  if (state.status === 'loading') return <ViewSkeleton />

  if (state.status !== 'done') {
    return (
      <div className="max-w-xl">
        <p className="text-2xl font-semibold tracking-tight">
          {state.status === 'missing' ? '이 결과를 찾을 수 없어요.' : '저장한 결과를 불러오지 못했어요.'}
        </p>
        <p className="mt-2 text-base text-zinc-500">
          {state.status === 'missing' ? '삭제되었거나 주소가 잘못됐어요.' : '잠시 후 다시 시도해 주세요.'}
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          {state.status === 'error' && (
            <button
              type="button"
              onClick={() => {
                setState({ status: 'loading' })
                setAttempt((n) => n + 1)
              }}
              className="rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
            >
              다시 시도
            </button>
          )}
          <button type="button" onClick={onHome} className={button}>
            <House size={16} /> 처음으로
          </button>
        </div>
      </div>
    )
  }

  const { entry } = state
  const { best, alternatives, places } = entry.result

  return (
    <div className="w-full">
      <div className="grid w-full gap-10 md:grid-cols-[1.4fr_1fr] md:items-start">
        <motion.div initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={spring}>
          <p className="text-sm font-medium text-accent">
            저장한 결과 · <span className="font-mono">{savedDate(entry.createdAt)}</span>
          </p>
          <p className="mt-2 text-sm text-zinc-500">{entry.result.title}</p>
          <h1 className="mt-4 text-5xl font-semibold leading-none tracking-tighter md:text-7xl">{best.name}</h1>
          <p className="mt-6 max-w-[45ch] text-base leading-relaxed text-zinc-600">{best.description}</p>
          {justSaved && (
            <p role="status" className="mt-6 flex items-center gap-2 text-sm font-medium">
              <CheckCircle size={18} weight="fill" className="text-accent" /> 저장했어요
            </p>
          )}
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <ShareButton id={entry.id} />
            <button type="button" onClick={onHome} className={button}>
              <House size={16} /> 처음으로
            </button>
            {entry.mine && (
              <button type="button" onClick={remove} disabled={deleting === 'busy'} className={button}>
                <Trash size={16} /> 삭제
              </button>
            )}
          </div>
          {deleting === 'failed' && (
            <p role="alert" className="mt-3 text-sm text-accent">
              삭제하지 못했어요. 다시 시도해 주세요.
            </p>
          )}
        </motion.div>
        {alternatives.length > 0 && (
          <div className="md:pt-24">
            <p className="text-sm text-zinc-500">이것도 괜찮아요</p>
            <ul className="mt-4 divide-y divide-zinc-200 border-y border-zinc-200">
              {alternatives.map((food) => (
                <li key={food.id} className="py-5">
                  <p className="text-xl font-semibold tracking-tight">{food.name}</p>
                  <p className="mt-1 text-sm leading-relaxed text-zinc-500">{food.description}</p>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      {places && (
        <section className="mt-20 border-t border-zinc-200 pt-12">
          <p className="flex items-center gap-2 text-sm text-zinc-500">
            <MapPin size={16} className="text-accent" /> {places.origin.name} 기준
          </p>
          <div className="mt-6">
            <PlaceTable food={best.name} nearby={places.nearby} famous={places.famous} />
          </div>
          {places.dateCourses.length > 0 && (
            <div className="mt-16">
              <DateCourses courses={places.dateCourses} />
            </div>
          )}
        </section>
      )}
    </div>
  )
}

function ViewSkeleton() {
  return (
    <div className="animate-pulse" aria-hidden>
      <div className="h-4 w-40 rounded-full bg-zinc-200" />
      <div className="mt-6 h-12 w-56 rounded-2xl bg-zinc-200 md:h-[4.5rem] md:w-80" />
      <div className="mt-6 h-4 w-72 max-w-full rounded-full bg-zinc-200" />
      <div className="mt-8 h-11 w-64 max-w-full rounded-full bg-zinc-200" />
    </div>
  )
}
