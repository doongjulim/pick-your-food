import { PencilSimple } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { updateSaved } from './api.ts'

type Props = {
  id: string
  title: string
  mine: boolean
  onRenamed: (title: string) => void
}

// the result's name; its owner can rename it in place
export default function SavedTitle({ id, title, mine, onRenamed }: Props) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(title)
  const [status, setStatus] = useState<'idle' | 'busy' | 'failed'>('idle')
  const input = useRef<HTMLInputElement>(null)
  const edit = useRef<HTMLButtonElement>(null)
  const opened = useRef(false)

  // focus moves into the form when it opens and back to the button when it closes
  useEffect(() => {
    if (editing) input.current?.focus()
    else if (opened.current) edit.current?.focus()
    opened.current = editing
  }, [editing])

  function open() {
    setDraft(title)
    setStatus('idle')
    setEditing(true)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = draft.trim()
    if (!next || status === 'busy') return
    setStatus('busy')
    updateSaved(id, { title: next }).then(
      () => {
        onRenamed(next)
        setStatus('idle')
        setEditing(false)
      },
      () => setStatus('failed'),
    )
  }

  if (!editing) {
    return (
      <p className="mt-2 flex items-center gap-1 text-sm text-zinc-500">
        <span className="min-w-0 break-words">{title}</span>
        {mine && (
          <button
            ref={edit}
            type="button"
            aria-label="이름 바꾸기"
            onClick={open}
            className="shrink-0 rounded-full p-2 text-zinc-500 transition hover:bg-zinc-100 hover:text-zinc-900 active:scale-[0.98]"
          >
            <PencilSimple size={16} />
          </button>
        )}
      </p>
    )
  }

  return (
    <form
      onSubmit={submit}
      onKeyDown={(event) => event.key === 'Escape' && setEditing(false)}
      className="mt-2 grid max-w-md gap-2"
    >
      <label htmlFor="saved-title" className="text-sm text-zinc-500">
        이름
      </label>
      <div className="flex flex-wrap gap-2">
        <input
          id="saved-title"
          ref={input}
          required
          maxLength={100}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          className="min-w-0 flex-1 basis-40 rounded-full border border-zinc-300 bg-white px-5 py-3 text-sm outline-none transition focus:border-accent"
        />
        <button
          type="submit"
          disabled={status === 'busy' || !draft.trim()}
          className="rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-40"
        >
          저장
        </button>
        <button
          type="button"
          onClick={() => setEditing(false)}
          className="rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
        >
          취소
        </button>
      </div>
      {status === 'failed' && (
        <p role="alert" className="text-sm text-accent">
          이름을 바꾸지 못했어요. 다시 시도해 주세요.
        </p>
      )}
    </form>
  )
}
