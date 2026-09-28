import { House, SignIn, Trash } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { deleteSaved, fetchMySaved, savedDate } from './api.ts'
import type { SavedSummary } from './api.ts'

type State = { status: 'loading' | 'out' | 'error' } | { status: 'done'; items: SavedSummary[] }

type Props = {
  onOpen: (id: string) => void
  onHome: () => void
}

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

const button =
  'flex w-fit items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]'

export default function SavedList({ onOpen, onHome }: Props) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [deleteFailed, setDeleteFailed] = useState(false)

  useEffect(() => {
    let current = true
    fetchMySaved().then(
      (items) => current && setState(items ? { status: 'done', items } : { status: 'out' }),
      () => current && setState({ status: 'error' }),
    )
    return () => {
      current = false
    }
  }, [attempt])

  function remove(id: string) {
    if (!confirm('이 결과를 지울까요? 링크도 더 이상 열리지 않아요.')) return
    setDeleteFailed(false)
    deleteSaved(id).then(
      () => setState((prev) => (prev.status === 'done' ? { status: 'done', items: prev.items.filter((item) => item.id !== id) } : prev)),
      () => setDeleteFailed(true),
    )
  }

  return (
    <div className="w-full max-w-3xl">
      <p className="text-sm font-medium text-accent">저장한 결과</p>
      {state.status === 'loading' && <ListSkeleton />}
      {state.status === 'out' && (
        <div className="mt-6">
          <p className="text-2xl font-semibold tracking-tight">로그인하면 저장한 결과를 볼 수 있어요.</p>
          <a href="/oauth2/authorization/kakao" className={`mt-8 ${button}`}>
            <SignIn size={16} /> 카카오로 로그인
          </a>
        </div>
      )}
      {state.status === 'error' && (
        <div className="mt-6">
          <p className="text-2xl font-semibold tracking-tight">저장한 결과를 불러오지 못했어요.</p>
          <button
            type="button"
            onClick={() => {
              setState({ status: 'loading' })
              setAttempt((n) => n + 1)
            }}
            className="mt-8 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
          >
            다시 시도
          </button>
        </div>
      )}
      {state.status === 'done' && state.items.length === 0 && (
        <div className="mt-6">
          <p className="text-2xl font-semibold tracking-tight">아직 저장한 결과가 없어요.</p>
          <p className="mt-2 text-base text-zinc-500">메뉴를 뽑고 저장해 보세요.</p>
        </div>
      )}
      {state.status === 'done' && state.items.length > 0 && (
        <ul className="mt-6 divide-y divide-zinc-200 border-y border-zinc-200">
          {state.items.map((item, index) => (
            <motion.li
              key={item.id}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ ...spring, delay: index * 0.05 }}
              className="flex items-center gap-2"
            >
              <button
                type="button"
                onClick={() => onOpen(item.id)}
                className="min-w-0 flex-1 py-5 text-left transition hover:bg-zinc-100/60"
              >
                <span className="block truncate text-lg font-semibold tracking-tight">{item.foodName}</span>
                <span className="mt-1 block truncate text-sm text-zinc-500">
                  {[item.title, item.originName && `${item.originName} 기준`].filter(Boolean).join(' · ')}
                </span>
              </button>
              <span className="shrink-0 font-mono text-sm text-zinc-500">{savedDate(item.createdAt)}</span>
              <button
                type="button"
                aria-label={`${item.foodName} 삭제`}
                onClick={() => remove(item.id)}
                className="shrink-0 rounded-full p-3 text-zinc-500 transition hover:bg-zinc-100 hover:text-zinc-900 active:scale-[0.98]"
              >
                <Trash size={18} />
              </button>
            </motion.li>
          ))}
        </ul>
      )}
      {deleteFailed && (
        <p role="alert" className="mt-3 text-sm text-accent">
          삭제하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      <button type="button" onClick={onHome} className={`mt-8 ${button}`}>
        <House size={16} /> 처음으로
      </button>
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="mt-6 animate-pulse divide-y divide-zinc-200" aria-hidden>
      {[0, 1, 2].map((row) => (
        <div key={row} className="flex items-center justify-between py-5">
          <div className="h-5 w-40 rounded-full bg-zinc-200" />
          <div className="h-4 w-16 rounded-full bg-zinc-200" />
        </div>
      ))}
    </div>
  )
}
