import { Coffee, ForkKnife, Mountains } from '@phosphor-icons/react'
import type { Icon } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import type { DateCourse as Course } from './api.ts'
import { distance } from './format.ts'
import MapLink from './MapLink.tsx'

type Step =
  | { icon: Icon; label: string; name: string; detail: string; url: string }
  | { icon: Icon; label: string; missing: string }

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

export default function DateCourse({ course }: { course: Course }) {
  const { restaurant, cafe, sight } = course
  const steps: Step[] = [
    { icon: ForkKnife, label: '식사', name: restaurant.name, detail: restaurant.address, url: restaurant.kakaoUrl },
    cafe
      ? { icon: Coffee, label: '카페', name: cafe.name, detail: `${cafe.category} · 식당에서 ${distance(cafe.distanceMeters)}`, url: cafe.kakaoUrl }
      : { icon: Coffee, label: '카페', missing: '근처에 카페를 찾지 못했어요' },
    sight
      ? { icon: Mountains, label: '볼거리', name: sight.name, detail: `${sight.category} · 식당에서 ${distance(sight.distanceMeters)}`, url: sight.kakaoUrl }
      : { icon: Mountains, label: '볼거리', missing: '근처에 볼거리를 찾지 못했어요' },
  ]

  return (
    <div>
      <p className="text-sm font-medium text-accent">데이트 코스</p>
      <h3 className="mt-2 text-2xl font-semibold tracking-tight">{restaurant.name}에서 시작해요</h3>
      <ol className="mt-8 grid gap-8 md:grid-cols-3 md:gap-6">
        {steps.map((step, index) => (
          <motion.li
            key={step.label}
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ ...spring, delay: index * 0.15 }}
            className="border-l border-zinc-300 pl-6 md:border-l-0 md:border-t md:pl-0 md:pt-6"
          >
            <p className="flex items-center gap-2 text-sm text-zinc-500">
              <span className="font-mono text-accent">{String(index + 1).padStart(2, '0')}</span>
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
    </div>
  )
}
