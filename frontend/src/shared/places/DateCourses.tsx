import { ArrowsClockwise, Coffee, ForkKnife, MapTrifold, Mountains, PersonSimpleWalk } from '@phosphor-icons/react'
import type { Icon } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { DateCourse, Leg, Spot } from './types.ts'
import { distance } from './format.ts'
import MapLink from './MapLink.tsx'
import CourseMap from './CourseMap.tsx'

type Kind = 'cafe' | 'sight'

// lets a search swap a course's cafe or sight for another candidate (values are course indexes); the saved view leaves it out
export type SpotSwapper = { change: (course: number, kind: Kind, spot: Spot) => void; pending: number | null; failed: number | null }

// leg is the walk into this step, shown above it; choice is the candidates a cafe or sight can be swapped to
type Step = { icon: Icon; label: string; leg: string | null } & (
  | { name: string; detail: string; url: string; choice?: { kind: Kind; current: string; candidates: Spot[] } }
  | { missing: string }
)

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

const number = (index: number) => String(index + 1).padStart(2, '0')

function walk(leg: Leg, fromRestaurant: boolean): string {
  return `${fromRestaurant ? '식당에서 ' : ''}도보 ${leg.walkMinutes}분 · ${distance(leg.meters)}`
}

// starts on the initial course (the first by default); PlacesSection remounts this for every new search and picked course
export default function DateCourses({ courses, initial = 0, swap }: { courses: DateCourse[]; initial?: number; swap?: SpotSwapper }) {
  const [selected, setSelected] = useState(initial)
  // which step's candidate list is open; one at a time, and closed on another tab
  const [open, setOpen] = useState<Kind | null>(null)
  const { restaurant, cafe, sight, cafes = [], sights = [], legs, routeUrl, picked } = courses[selected]
  const busy = swap?.pending === selected
  const steps: Step[] = [
    { icon: ForkKnife, label: '식사', leg: null, name: restaurant.name, detail: restaurant.address, url: restaurant.kakaoUrl },
    cafe
      ? {
          icon: Coffee,
          label: '카페',
          leg: walk(legs[0], false),
          name: cafe.name,
          detail: cafe.category,
          url: cafe.kakaoUrl,
          choice: { kind: 'cafe', current: cafe.id, candidates: cafes },
        }
      : { icon: Coffee, label: '카페', leg: null, missing: '근처에 카페를 찾지 못했어요' },
    sight
      ? // the sight's leg is always the last one; without a cafe it starts at the restaurant
        {
          icon: Mountains,
          label: '볼거리',
          leg: walk(legs[legs.length - 1], !cafe),
          name: sight.name,
          detail: sight.category,
          url: sight.kakaoUrl,
          choice: { kind: 'sight', current: sight.id, candidates: sights },
        }
      : { icon: Mountains, label: '볼거리', leg: null, missing: '근처에 볼거리를 찾지 못했어요' },
  ]
  const straight = legs.filter((leg) => !leg.path).length
  const note = straight === 0 ? null : straight === legs.length ? '직선거리 기준 예상 시간이에요' : '일부 구간은 직선거리 기준이에요'

  return (
    <div>
      <p className="text-sm font-medium text-accent">{picked ? '내 데이트 코스' : '데이트 코스'}</p>
      <h3 className="mt-2 text-2xl font-semibold tracking-tight">{restaurant.name}에서 시작해요</h3>
      {courses.length > 1 && (
        <div className="mt-6 flex flex-wrap gap-2">
          {courses.map((course, index) => (
            <button
              key={course.restaurant.id}
              type="button"
              onClick={() => {
                setSelected(index)
                setOpen(null)
              }}
              className="relative max-w-full rounded-full px-4 py-2 text-sm font-medium transition active:scale-[0.98]"
            >
              {index === selected && (
                <motion.span layoutId="course-tab" className="absolute inset-0 rounded-full bg-zinc-900" transition={spring} />
              )}
              <span className={`relative block truncate ${index === selected ? 'text-white' : 'text-zinc-600'}`}>
                <span className="font-mono">{number(index)}</span>{' '}
                {course.picked && <span className={index === selected ? 'text-accent-soft' : 'text-accent'}>내 코스 </span>}
                {course.restaurant.name}
              </span>
            </button>
          ))}
        </div>
      )}
      <AnimatePresence mode="wait">
        <motion.ol
          key={selected}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={spring}
          className="mt-8 grid gap-8 md:grid-cols-3 md:gap-6"
        >
          {steps.map((step, index) => (
            <motion.li
              key={step.label}
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ ...spring, delay: index * 0.15 }}
              className="border-l border-zinc-300 pl-6 md:border-l-0 md:border-t md:pl-0 md:pt-6"
            >
              {/* keeps the steps level on desktop even when a step has no leg */}
              <p className={`mb-3 flex h-4 items-center gap-1.5 font-mono text-xs text-zinc-500 ${step.leg ? '' : 'max-md:hidden'}`}>
                {step.leg && (
                  <>
                    <PersonSimpleWalk size={14} /> {busy ? '경로 다시 계산 중…' : step.leg}
                  </>
                )}
              </p>
              <p className="flex items-center gap-2 text-sm text-zinc-500">
                <span className="font-mono text-accent">{number(index)}</span>
                <step.icon size={16} /> {step.label}
              </p>
              {'missing' in step ? (
                <p className="mt-3 text-base text-zinc-400">{step.missing}</p>
              ) : (
                <>
                  <p className="mt-3 text-xl font-semibold tracking-tight">{step.name}</p>
                  <p className="mt-1 text-sm text-zinc-500">{step.detail}</p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <MapLink href={step.url} label="카카오맵" />
                    {swap && step.choice && step.choice.candidates.length > 1 && (
                      <SwapButton
                        kind={step.choice.kind}
                        open={open === step.choice.kind}
                        disabled={busy}
                        onToggle={(kind) => setOpen(open === kind ? null : kind)}
                      />
                    )}
                  </div>
                  {swap && step.choice && open === step.choice.kind && (
                    <Candidates
                      choice={step.choice}
                      onPick={(kind, spot) => {
                        setOpen(null)
                        swap.change(selected, kind, spot)
                      }}
                    />
                  )}
                </>
              )}
            </motion.li>
          ))}
        </motion.ol>
      </AnimatePresence>
      {swap?.failed === selected && (
        <p role="alert" className="mt-6 text-sm text-accent">
          바꾸지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      {/* a restaurant-only course has nothing to draw */}
      {legs.length > 0 && <CourseMap course={courses[selected]} />}
      {routeUrl && (
        <div className="mt-8 flex flex-wrap items-center gap-x-4 gap-y-2">
          <a
            href={routeUrl}
            target="_blank"
            rel="noreferrer"
            className="flex w-fit items-center gap-2 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
          >
            <MapTrifold size={16} /> 전체 경로 보기
          </a>
          {note && <p className="text-sm text-zinc-500">{note}</p>}
        </div>
      )}
    </div>
  )
}

function SwapButton({ kind, open, disabled, onToggle }: { kind: Kind; open: boolean; disabled: boolean; onToggle: (kind: Kind) => void }) {
  return (
    <button
      type="button"
      onClick={() => onToggle(kind)}
      disabled={disabled}
      aria-expanded={open}
      className="flex w-fit items-center gap-2 rounded-full border border-accent px-4 py-2 text-sm font-medium text-accent transition active:scale-[0.98] disabled:opacity-40"
    >
      <ArrowsClockwise size={16} /> 다른 곳
    </button>
  )
}

function Candidates({ choice, onPick }: { choice: { kind: Kind; current: string; candidates: Spot[] }; onPick: (kind: Kind, spot: Spot) => void }) {
  return (
    <ul className="mt-4 divide-y divide-zinc-200 border-y border-zinc-200">
      {choice.candidates.map((spot) => {
        const current = spot.id === choice.current
        return (
          <li key={spot.id}>
            <button
              type="button"
              onClick={() => onPick(choice.kind, spot)}
              disabled={current}
              className="flex w-full items-baseline justify-between gap-3 py-3 text-left transition hover:text-accent disabled:hover:text-inherit"
            >
              <span className="min-w-0">
                <span className="block truncate text-sm font-medium">{spot.name}</span>
                <span className="block truncate text-xs text-zinc-500">{spot.category}</span>
              </span>
              <span className="shrink-0 font-mono text-xs text-zinc-500">
                {current && <span className="text-accent">지금 </span>}
                {spot.distanceMeters !== undefined && distance(spot.distanceMeters)}
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}
