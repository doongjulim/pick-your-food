import { Coffee, ForkKnife, MapTrifold, Mountains, PersonSimpleWalk } from '@phosphor-icons/react'
import type { Icon } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { DateCourse, Leg } from './api.ts'
import { distance } from './format.ts'
import MapLink from './MapLink.tsx'

// leg is the walk into this step, shown above it
type Step = { icon: Icon; label: string; leg: string | null } & (
  | { name: string; detail: string; url: string }
  | { missing: string }
)

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

const number = (index: number) => String(index + 1).padStart(2, '0')

function walk(leg: Leg, fromRestaurant: boolean): string {
  return `${fromRestaurant ? '식당에서 ' : ''}도보 ${leg.walkMinutes}분 · ${distance(leg.meters)}`
}

// starts on the first course; PlacesSection remounts this for every new search
export default function DateCourses({ courses }: { courses: DateCourse[] }) {
  const [selected, setSelected] = useState(0)
  const { restaurant, cafe, sight, legs, routeUrl } = courses[selected]
  const steps: Step[] = [
    { icon: ForkKnife, label: '식사', leg: null, name: restaurant.name, detail: restaurant.address, url: restaurant.kakaoUrl },
    cafe
      ? { icon: Coffee, label: '카페', leg: walk(legs[0], false), name: cafe.name, detail: cafe.category, url: cafe.kakaoUrl }
      : { icon: Coffee, label: '카페', leg: null, missing: '근처에 카페를 찾지 못했어요' },
    sight
      ? // the sight's leg is always the last one; without a cafe it starts at the restaurant
        { icon: Mountains, label: '볼거리', leg: walk(legs[legs.length - 1], !cafe), name: sight.name, detail: sight.category, url: sight.kakaoUrl }
      : { icon: Mountains, label: '볼거리', leg: null, missing: '근처에 볼거리를 찾지 못했어요' },
  ]

  return (
    <div>
      <p className="text-sm font-medium text-accent">데이트 코스</p>
      <h3 className="mt-2 text-2xl font-semibold tracking-tight">{restaurant.name}에서 시작해요</h3>
      {courses.length > 1 && (
        <div className="mt-6 flex flex-wrap gap-2">
          {courses.map((course, index) => (
            <button
              key={course.restaurant.id}
              type="button"
              onClick={() => setSelected(index)}
              className="relative max-w-full rounded-full px-4 py-2 text-sm font-medium transition active:scale-[0.98]"
            >
              {index === selected && (
                <motion.span layoutId="course-tab" className="absolute inset-0 rounded-full bg-zinc-900" transition={spring} />
              )}
              <span className={`relative block truncate ${index === selected ? 'text-white' : 'text-zinc-600'}`}>
                <span className="font-mono">{number(index)}</span> {course.restaurant.name}
              </span>
            </button>
          ))}
        </div>
      )}
      <AnimatePresence mode="wait">
        <motion.div
          key={selected}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={spring}
        >
          <ol className="mt-8 grid gap-8 md:grid-cols-3 md:gap-6">
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
                      <PersonSimpleWalk size={14} /> {step.leg}
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
                    <div className="mt-4">
                      <MapLink href={step.url} label="카카오맵" />
                    </div>
                  </>
                )}
              </motion.li>
            ))}
          </ol>
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
              <p className="text-sm text-zinc-500">직선거리 기준 예상 시간이에요</p>
            </div>
          )}
        </motion.div>
      </AnimatePresence>
    </div>
  )
}
