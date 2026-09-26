import { AnimatePresence, motion } from 'motion/react'
import { useRef, useState } from 'react'
import ModeSelect from './features/mode-select/ModeSelect.tsx'
import { fetchRandom } from './features/random/api.ts'
import { fetchRecommendation } from './features/recommendation/api.ts'
import type { Recommendation } from './features/recommendation/api.ts'
import Questionnaire from './features/recommendation/Questionnaire.tsx'
import type { Answers } from './features/recommendation/questions.ts'
import type { Food } from './shared/api.ts'
import ResultView from './shared/ResultView.tsx'

type Screen = 'home' | 'random' | 'survey' | 'recommend'

type Result =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'done'; best: Food; alternatives: Food[] }

export default function App() {
  const [screen, setScreen] = useState<Screen>('home')
  const [result, setResult] = useState<Result>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const latest = useRef(0)
  const lastRequest = useRef<() => void>(() => {})

  function run(next: Screen, fetcher: () => Promise<Recommendation>) {
    // responses from an older request (or after going home) are ignored
    const id = ++latest.current
    lastRequest.current = () => run(next, fetcher)
    setScreen(next)
    setAttempt(id)
    setResult({ status: 'loading' })
    fetcher().then(
      (data) => id === latest.current && setResult({ status: 'done', ...data }),
      () => id === latest.current && setResult({ status: 'error' }),
    )
  }

  function drawRandom() {
    run('random', () => fetchRandom().then((food) => ({ best: food, alternatives: [] })))
  }

  function recommend(answers: Answers) {
    run('recommend', () => fetchRecommendation(answers))
  }

  function goHome() {
    latest.current++
    setScreen('home')
  }

  return (
    <main className="mx-auto flex min-h-[100dvh] w-full max-w-6xl items-center px-4 py-8 md:px-12">
      <AnimatePresence mode="wait">
        <motion.div
          key={screen === 'home' || screen === 'survey' ? screen : `${screen}-${attempt}`}
          className="w-full"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
        >
          {screen === 'home' && <ModeSelect onRandom={drawRandom} onSurvey={() => setScreen('survey')} />}
          {screen === 'survey' && <Questionnaire onComplete={recommend} onExit={goHome} />}
          {(screen === 'random' || screen === 'recommend') && (
            <ResultView
              title={screen === 'random' ? '오늘의 랜덤 메뉴' : '당신에게 딱 맞는 메뉴'}
              status={result.status}
              best={result.status === 'done' ? result.best : undefined}
              alternatives={result.status === 'done' ? result.alternatives : []}
              roll={screen === 'random'}
              againLabel={screen === 'random' ? '다시 뽑기' : '다시 하기'}
              onAgain={screen === 'random' ? drawRandom : () => setScreen('survey')}
              onRetry={() => lastRequest.current()}
              onHome={goHome}
            />
          )}
        </motion.div>
      </AnimatePresence>
    </main>
  )
}
