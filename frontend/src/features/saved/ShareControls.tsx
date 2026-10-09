import { useState } from 'react'
import { updateSaved } from './api.ts'
import ShareButton from './ShareButton.tsx'

type Props = {
  id: string
  mine: boolean
  shared: boolean
  onChange: (shared: boolean) => void
}

// visitors get the link; the owner can also turn it off, which makes the link look deleted to everyone else
export default function ShareControls({ id, mine, shared, onChange }: Props) {
  const [status, setStatus] = useState<'idle' | 'busy' | 'failed'>('idle')

  if (!mine) return <ShareButton id={id} />

  function toggle() {
    setStatus('busy')
    updateSaved(id, { shared: !shared }).then(
      () => {
        onChange(!shared)
        setStatus('idle')
      },
      () => setStatus('failed'),
    )
  }

  return (
    <>
      <button
        type="button"
        role="switch"
        aria-checked={shared}
        onClick={toggle}
        disabled={status === 'busy'}
        className="flex items-center gap-2 rounded-full border border-zinc-300 py-2.5 pl-2.5 pr-5 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
      >
        <span aria-hidden className={`flex h-6 w-10 items-center rounded-full p-0.5 transition ${shared ? 'bg-accent' : 'bg-zinc-300'}`}>
          <span className={`size-5 rounded-full bg-white shadow-sm transition ${shared ? 'translate-x-4' : ''}`} />
        </span>
        공유
      </button>
      {shared ? (
        <ShareButton id={id} />
      ) : (
        <p className="basis-full text-sm text-zinc-500">공유가 꺼져 있어요. 다른 사람은 이 링크를 열 수 없어요.</p>
      )}
      {status === 'failed' && (
        <p role="alert" className="basis-full text-sm text-accent">
          공유 설정을 바꾸지 못했어요. 다시 시도해 주세요.
        </p>
      )}
    </>
  )
}
