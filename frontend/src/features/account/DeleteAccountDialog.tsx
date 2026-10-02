import { useEffect, useRef, useState } from 'react'
import { deleteAccount } from './api.ts'

// the browser's modal dialog traps focus and closes on Esc; Esc is ignored while the deletion runs
export default function DeleteAccountDialog({ onClose, onDeleted }: { onClose: () => void; onDeleted: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [deleting, setDeleting] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    dialog.current?.showModal()
  }, [])

  function confirm() {
    setDeleting(true)
    setFailed(false)
    deleteAccount().then(onDeleted, () => {
      setDeleting(false)
      setFailed(true)
    })
  }

  return (
    <dialog
      ref={dialog}
      aria-labelledby="delete-account-title"
      onCancel={(event) => {
        event.preventDefault()
        if (!deleting) onClose()
      }}
      className="m-auto w-[min(26rem,calc(100%-2rem))] rounded-3xl border border-zinc-200 bg-white p-8 text-zinc-900 backdrop:bg-zinc-950/40"
    >
      <h2 id="delete-account-title" className="text-xl font-semibold tracking-tight">
        정말 탈퇴할까요?
      </h2>
      <p className="mt-3 text-sm leading-relaxed text-zinc-600">
        저장한 결과와 공유 링크가 모두 사라지고 되돌릴 수 없어요. 카카오 계정 연결도 끊어져요.
      </p>
      {failed && (
        <p role="alert" className="mt-4 text-sm text-accent">
          탈퇴하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      <div className="mt-8 flex justify-end gap-2">
        <button
          type="button"
          disabled={deleting}
          onClick={onClose}
          className="rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
        >
          취소
        </button>
        <button
          type="button"
          disabled={deleting}
          onClick={confirm}
          className="rounded-full bg-accent px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-60"
        >
          {deleting ? '탈퇴하는 중…' : '탈퇴하기'}
        </button>
      </div>
    </dialog>
  )
}
