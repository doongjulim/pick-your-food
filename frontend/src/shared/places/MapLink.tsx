import { ArrowUpRight } from '@phosphor-icons/react'

export default function MapLink({ href, label }: { href: string; label: string }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className="flex w-fit items-center gap-1 rounded-full border border-zinc-300 px-4 py-2 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
    >
      {label} <ArrowUpRight size={14} />
    </a>
  )
}
