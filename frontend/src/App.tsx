import ModeSelect from './ModeSelect.tsx'

export default function App() {
  return (
    <main className="mx-auto flex min-h-[100dvh] w-full max-w-6xl items-center px-4 py-8 md:px-12">
      <ModeSelect onRandom={() => {}} onSurvey={() => {}} />
    </main>
  )
}
