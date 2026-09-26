import { ArrowLeft } from '@phosphor-icons/react'
import { AnimatePresence, motion, useIsPresent } from 'motion/react'
import { useState } from 'react'
import { QUESTIONS } from './questions.ts'
import type { Answers, Question } from './questions.ts'

type Props = {
  onComplete: (answers: Answers) => void
  onExit: () => void
}

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

export default function Questionnaire({ onComplete, onExit }: Props) {
  const [step, setStep] = useState(0)
  const [answers, setAnswers] = useState<Partial<Answers>>({})
  const question = QUESTIONS[step]

  function choose(value: string) {
    const next = { ...answers, [question.key]: value }
    setAnswers(next)
    if (step === QUESTIONS.length - 1) onComplete(next as Answers)
    else setStep(step + 1)
  }

  return (
    <div className="grid w-full gap-10 md:grid-cols-[1fr_1.2fr]">
      <div>
        <button
          type="button"
          onClick={() => (step === 0 ? onExit() : setStep(step - 1))}
          className="flex items-center gap-2 text-sm text-zinc-500 transition hover:text-zinc-900 active:scale-[0.98]"
        >
          <ArrowLeft size={16} /> 뒤로
        </button>
        <p className="mt-8 font-mono text-sm text-accent">
          {step + 1} / {QUESTIONS.length}
        </p>
        <div className="mt-3 h-1 w-40 overflow-hidden rounded-full bg-zinc-200">
          <motion.div
            className="h-full origin-left bg-accent"
            initial={false}
            animate={{ scaleX: (step + 1) / QUESTIONS.length }}
            transition={spring}
          />
        </div>
      </div>
      <AnimatePresence mode="wait">
        <motion.div
          key={step}
          initial={{ opacity: 0, x: 40 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: -40 }}
          transition={spring}
        >
          <QuestionStep question={question} selected={answers[question.key]} onChoose={choose} />
        </motion.div>
      </AnimatePresence>
    </div>
  )
}

type QuestionStepProps = {
  question: Question
  selected: string | undefined
  onChoose: (value: string) => void
}

function QuestionStep({ question, selected, onChoose }: QuestionStepProps) {
  // false while this screen animates out: a second tap here must not answer the next question
  const isPresent = useIsPresent()

  return (
    <>
      <h2 className="text-3xl font-semibold tracking-tight md:text-4xl">{question.title}</h2>
      <div className="mt-8 grid grid-cols-2 gap-3">
        {question.options.map((option) => (
          <button
            key={option.value}
            type="button"
            onClick={() => isPresent && onChoose(option.value)}
            className={`rounded-2xl border p-5 text-left text-lg font-medium transition duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] hover:border-accent active:scale-[0.98] ${
              selected === option.value ? 'border-accent bg-accent-soft' : 'border-zinc-200 bg-white'
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </>
  )
}
