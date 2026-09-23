import { ArrowCounterClockwise, House } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import type { Food } from './api.ts'

type Props = {
  title: string
  status: 'loading' | 'error' | 'done'
  best: Food | undefined
  alternatives: Food[]
  roll: boolean
  againLabel: string
  onAgain: () => void
  onRetry: () => void
  onHome: () => void
}

// only used for the slot-machine effect before the random result settles
const ROLL_NAMES = ['김치찌개', '짬뽕', '초밥', '파스타', '떡볶이', '비빔밥', '마라탕', '돈카츠', '햄버거', '순대국']

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

export default function ResultView({ title, status, best, alternatives, roll, againLabel, onAgain, onRetry, onHome }: Props) {
  return (
    <div className="grid w-full gap-10 md:grid-cols-[1.4fr_1fr] md:items-start">
      <div>
        <p className="text-sm font-medium text-accent">{title}</p>
        {status === 'loading' && <BestSkeleton />}
        {status === 'error' && (
          <div className="mt-6 rounded-3xl border border-zinc-200 bg-white p-8">
            <p className="text-lg font-semibold">메뉴를 불러오지 못했어요.</p>
            <p className="mt-2 text-sm text-zinc-500">서버가 켜져 있는지 확인한 뒤 다시 시도해 주세요.</p>
            <div className="mt-6 flex flex-wrap gap-3">
              <button
                type="button"
                onClick={onRetry}
                className="rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
              >
                다시 시도
              </button>
              <button
                type="button"
                onClick={onHome}
                className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
              >
                <House size={16} /> 처음으로
              </button>
            </div>
          </div>
        )}
        {status === 'done' && best && <BestFood food={best} roll={roll} />}
        {status !== 'error' && (
          <div className="mt-8 flex flex-wrap gap-3">
            <button
              type="button"
              onClick={onAgain}
              disabled={status === 'loading'}
              className="flex items-center gap-2 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-40"
            >
              <ArrowCounterClockwise size={16} /> {againLabel}
            </button>
            <button
              type="button"
              onClick={onHome}
              className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
            >
              <House size={16} /> 처음으로
            </button>
          </div>
        )}
      </div>
      {status === 'done' && alternatives.length > 0 && (
        <div className="md:pt-24">
          <p className="text-sm text-zinc-500">이것도 괜찮아요</p>
          <ul className="mt-4 divide-y divide-zinc-200 border-y border-zinc-200">
            {alternatives.map((food, index) => (
              <motion.li
                key={food.id}
                initial={{ opacity: 0, y: 16 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...spring, delay: 0.4 + index * 0.15 }}
                className="py-5"
              >
                <p className="text-xl font-semibold tracking-tight">{food.name}</p>
                <p className="mt-1 text-sm leading-relaxed text-zinc-500">{food.description}</p>
              </motion.li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function BestFood({ food, roll }: { food: Food; roll: boolean }) {
  const shown = useRoll(food.name, roll)

  return (
    <motion.div initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={spring}>
      <h1 className="mt-4 text-5xl font-semibold leading-none tracking-tighter md:text-7xl">{shown}</h1>
      <p className="mt-6 max-w-[45ch] text-base leading-relaxed text-zinc-600">
        {shown === food.name ? food.description : ' '}
      </p>
    </motion.div>
  )
}

// cycles through ROLL_NAMES for about a second, then settles on the final name
function useRoll(finalName: string, roll: boolean) {
  const [shown, setShown] = useState(roll ? ROLL_NAMES[0] : finalName)

  useEffect(() => {
    if (!roll) return
    let tick = 0
    const timer = setInterval(() => {
      tick += 1
      if (tick >= 14) {
        setShown(finalName)
        clearInterval(timer)
      } else {
        setShown(ROLL_NAMES[tick % ROLL_NAMES.length])
      }
    }, 70)
    return () => clearInterval(timer)
  }, [finalName, roll])

  return shown
}

function BestSkeleton() {
  return (
    <div className="mt-4 animate-pulse">
      <div className="h-12 w-56 rounded-2xl bg-zinc-200 md:h-[4.5rem] md:w-80" />
      <div className="mt-6 h-4 w-72 max-w-full rounded-full bg-zinc-200" />
      <div className="mt-3 h-4 w-52 rounded-full bg-zinc-200" />
    </div>
  )
}
