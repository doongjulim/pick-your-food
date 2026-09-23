import { ArrowRight, ListChecks, Shuffle } from '@phosphor-icons/react'
import type { Icon } from '@phosphor-icons/react'

type Props = {
  onRandom: () => void
  onSurvey: () => void
}

export default function ModeSelect({ onRandom, onSurvey }: Props) {
  return (
    <div className="grid w-full gap-12 md:grid-cols-[1.1fr_1fr] md:items-end">
      <div>
        <p className="text-sm font-medium text-accent">Pick your food</p>
        <h1 className="mt-4 text-4xl font-semibold leading-none tracking-tighter md:text-6xl">
          오늘 뭐 먹지?
        </h1>
        <p className="mt-6 max-w-[40ch] text-base leading-relaxed text-zinc-600">
          고민할 시간에 먹자. 바로 하나 뽑거나, 다섯 가지 질문에 답하고 딱 맞는 메뉴를 받아보세요.
        </p>
      </div>
      <div className="grid gap-3">
        <ModeButton icon={Shuffle} title="랜덤으로 뽑기" hint="아무거나 하나, 지금 바로" onClick={onRandom} />
        <ModeButton icon={ListChecks} title="5가지 질문으로 찾기" hint="상황·기분·종류·배고픔·맛" onClick={onSurvey} />
      </div>
    </div>
  )
}

type ModeButtonProps = {
  icon: Icon
  title: string
  hint: string
  onClick: () => void
}

function ModeButton({ icon: IconComponent, title, hint, onClick }: ModeButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group flex items-center gap-4 rounded-3xl border border-zinc-200 bg-white p-6 text-left transition duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] hover:border-accent active:scale-[0.98]"
    >
      <IconComponent size={28} weight="duotone" className="text-accent" />
      <span className="flex-1">
        <span className="block text-lg font-semibold tracking-tight">{title}</span>
        <span className="block text-sm text-zinc-500">{hint}</span>
      </span>
      <ArrowRight size={20} className="text-zinc-400 transition group-hover:translate-x-1 group-hover:text-accent" />
    </button>
  )
}
