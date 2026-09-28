import { BookmarkSimple, CheckCircle } from '@phosphor-icons/react'
import { useState } from 'react'
import { HttpError } from '../../shared/api.ts'
import { saveResult } from './api.ts'
import type { Saved } from './api.ts'
import { stashPending } from './pending.ts'
import ShareButton from './ShareButton.tsx'

type State = { status: 'idle' | 'saving' | 'failed' } | { status: 'saved'; id: string }

// App remounts this (key) whenever the places change, so a new search can be saved again
export default function SaveButton({ saved }: { saved: Saved }) {
  const [state, setState] = useState<State>({ status: 'idle' })

  function save() {
    setState({ status: 'saving' })
    saveResult(saved).then(
      (id) => setState({ status: 'saved', id }),
      (error: unknown) => {
        if (error instanceof HttpError && error.status === 401) {
          // saved after the login comes back, see resumePending
          stashPending(saved)
          location.assign('/oauth2/authorization/kakao')
        } else {
          setState({ status: 'failed' })
        }
      },
    )
  }

  return (
    <div className="mt-10 flex flex-wrap items-center gap-3">
      {state.status === 'saved' ? (
        <>
          <p className="flex items-center gap-2 text-sm font-medium">
            <CheckCircle size={18} weight="fill" className="text-accent" /> 저장했어요
          </p>
          <ShareButton id={state.id} />
        </>
      ) : (
        <button
          type="button"
          onClick={save}
          disabled={state.status === 'saving'}
          className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
        >
          <BookmarkSimple size={16} /> {state.status === 'saving' ? '저장하는 중…' : '저장하기'}
        </button>
      )}
      {state.status === 'failed' && (
        <p role="alert" className="text-sm text-accent">
          저장하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
    </div>
  )
}
