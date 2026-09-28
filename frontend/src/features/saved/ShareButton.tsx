import { LinkSimple } from '@phosphor-icons/react'
import { useState } from 'react'

// the share sheet where there is one (phones), otherwise the link goes to the clipboard
export default function ShareButton({ id }: { id: string }) {
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle')
  const url = `${location.origin}/s/${id}`

  function share() {
    if (navigator.share) {
      // closing the share sheet rejects; nothing to report
      navigator.share({ url }).catch(() => {})
      return
    }
    navigator.clipboard.writeText(url).then(
      () => setCopy('copied'),
      () => setCopy('failed'),
    )
  }

  return (
    <>
      <button
        type="button"
        onClick={share}
        className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
      >
        <LinkSimple size={16} /> 링크 공유
      </button>
      {copy === 'copied' && (
        <p role="status" className="text-sm text-zinc-500">
          링크를 복사했어요
        </p>
      )}
      {copy === 'failed' && <p className="basis-full break-all font-mono text-xs text-zinc-500">{url}</p>}
    </>
  )
}
