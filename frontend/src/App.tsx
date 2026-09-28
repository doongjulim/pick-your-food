import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import AccountMenu from './features/account/AccountMenu.tsx'
import ModeSelect from './features/mode-select/ModeSelect.tsx'
import PlacesSection from './features/places/PlacesSection.tsx'
import { fetchRandom } from './features/random/api.ts'
import { fetchRecommendation } from './features/recommendation/api.ts'
import type { Recommendation } from './features/recommendation/api.ts'
import Questionnaire from './features/recommendation/Questionnaire.tsx'
import type { Answers } from './features/recommendation/questions.ts'
import { resumePending } from './features/saved/pending.ts'
import SaveButton from './features/saved/SaveButton.tsx'
import SavedList from './features/saved/SavedList.tsx'
import SavedView from './features/saved/SavedView.tsx'
import type { Food } from './shared/api.ts'
import type { Places } from './shared/places/types.ts'
import ResultView from './shared/ResultView.tsx'

type Screen = 'home' | 'random' | 'survey' | 'recommend'

// the saved screens have their own address; everything else lives at /
type Page = { kind: 'main' } | { kind: 'list' } | { kind: 'view'; id: string }

type Result =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'done'; best: Food; alternatives: Food[] }

function pageOf(path: string): Page {
  const view = path.match(/^\/s\/([\w-]+)$/)
  if (view) return { kind: 'view', id: view[1] }
  return path === '/saved' ? { kind: 'list' } : { kind: 'main' }
}

export default function App() {
  const [page, setPage] = useState<Page>(() => pageOf(location.pathname))
  const [screen, setScreen] = useState<Screen>('home')
  const [result, setResult] = useState<Result>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [places, setPlaces] = useState<{ data: Places | null; version: number }>({ data: null, version: 0 })
  const [justSaved, setJustSaved] = useState<string | null>(null)
  const [saveFailed, setSaveFailed] = useState(false)
  const latest = useRef(0)
  const lastRequest = useRef<() => void>(() => {})

  useEffect(() => {
    const sync = () => setPage(pageOf(location.pathname))
    addEventListener('popstate', sync)
    return () => removeEventListener('popstate', sync)
  }, [])

  useEffect(() => {
    // back from the Kakao login with a result the visitor wanted to save
    resumePending()?.then(
      (id) => {
        setJustSaved(id)
        go(`/s/${id}`)
      },
      () => setSaveFailed(true),
    )
  }, [])

  function go(path: string) {
    history.pushState(null, '', path)
    setPage(pageOf(path))
  }

  function run(next: Screen, fetcher: () => Promise<Recommendation>) {
    // responses from an older request (or after going home) are ignored
    const id = ++latest.current
    lastRequest.current = () => run(next, fetcher)
    setScreen(next)
    setAttempt(id)
    setResult({ status: 'loading' })
    setPlaces((prev) => ({ data: null, version: prev.version + 1 }))
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
    setSaveFailed(false)
    if (page.kind !== 'main') go('/')
  }

  const title = screen === 'random' ? '오늘의 랜덤 메뉴' : '당신에게 딱 맞는 메뉴'
  const key =
    page.kind === 'view' ? `view-${page.id}` : page.kind === 'list' ? 'list' : screen === 'home' || screen === 'survey' ? screen : `${screen}-${attempt}`

  return (
    <main className="relative mx-auto flex min-h-[100dvh] w-full max-w-6xl items-center px-4 pb-8 pt-20 md:px-12">
      <AccountMenu onOpenSaved={() => go('/saved')} />
      {saveFailed && page.kind === 'main' && screen === 'home' && (
        <p role="alert" className="absolute left-4 top-20 text-sm text-accent md:left-12">
          저장하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      <AnimatePresence mode="wait">
        <motion.div
          key={key}
          className="w-full"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
        >
          {page.kind === 'list' && <SavedList onOpen={(id) => go(`/s/${id}`)} onHome={goHome} />}
          {page.kind === 'view' && (
            <SavedView id={page.id} justSaved={justSaved === page.id} onHome={goHome} onDeleted={() => go('/saved')} />
          )}
          {page.kind === 'main' && screen === 'home' && (
            <ModeSelect onRandom={drawRandom} onSurvey={() => setScreen('survey')} />
          )}
          {page.kind === 'main' && screen === 'survey' && <Questionnaire onComplete={recommend} onExit={goHome} />}
          {page.kind === 'main' && (screen === 'random' || screen === 'recommend') && (
            <>
              <ResultView
                title={title}
                status={result.status}
                best={result.status === 'done' ? result.best : undefined}
                alternatives={result.status === 'done' ? result.alternatives : []}
                roll={screen === 'random'}
                againLabel={screen === 'random' ? '다시 뽑기' : '다시 하기'}
                onAgain={screen === 'random' ? drawRandom : () => setScreen('survey')}
                onRetry={() => lastRequest.current()}
                onHome={goHome}
              />
              {result.status === 'done' && (
                <>
                  <SaveButton
                    key={places.version}
                    saved={{ title, best: result.best, alternatives: result.alternatives, places: places.data }}
                  />
                  <PlacesSection
                    food={result.best.name}
                    onLoaded={(data) => setPlaces((prev) => ({ data, version: prev.version + 1 }))}
                  />
                </>
              )}
            </>
          )}
        </motion.div>
      </AnimatePresence>
    </main>
  )
}
