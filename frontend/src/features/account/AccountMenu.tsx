import { BookmarksSimple, CaretDown, SignIn, SignOut, UserMinus } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { fetchMe, logout } from './api.ts'
import type { Me } from './api.ts'
import DeleteAccountDialog from './DeleteAccountDialog.tsx'

type Account = { status: 'checking' } | { status: 'out' } | { status: 'in'; me: Me }

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

// onOpenSaved comes from App: features don't import each other
export default function AccountMenu({ onOpenSaved }: { onOpenSaved: () => void }) {
  const [account, setAccount] = useState<Account>({ status: 'checking' })
  const [open, setOpen] = useState(false)
  const [loginFailed] = useState(() => new URLSearchParams(location.search).get('login') === 'failed')
  const [logoutFailed, setLogoutFailed] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleted, setDeleted] = useState(false)

  useEffect(() => {
    // the server being down reads as logged out; the login link still works once it is back
    fetchMe().then(
      (me) => setAccount(me ? { status: 'in', me } : { status: 'out' }),
      () => setAccount({ status: 'out' }),
    )
  }, [])

  useEffect(() => {
    if (!loginFailed) return
    const url = new URL(location.href)
    url.searchParams.delete('login')
    history.replaceState(null, '', url)
  }, [loginFailed])

  function signOut() {
    setLogoutFailed(false)
    logout().then(
      () => {
        setOpen(false)
        setAccount({ status: 'out' })
      },
      () => setLogoutFailed(true),
    )
  }

  return (
    <div className="absolute right-4 top-4 flex flex-col items-end md:right-12 md:top-6">
      {account.status === 'checking' && <div className="h-10 w-36 animate-pulse rounded-full bg-zinc-100" aria-hidden />}
      {account.status === 'out' && (
        <a
          href="/oauth2/authorization/kakao"
          className="flex h-10 items-center gap-2 rounded-full border border-zinc-300 bg-white px-4 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
        >
          <SignIn size={18} />
          카카오로 로그인
        </a>
      )}
      {account.status === 'in' && (
        <div className="relative">
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((value) => !value)}
            className="flex h-10 max-w-[14rem] items-center gap-2 rounded-full border border-zinc-300 bg-white px-4 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
          >
            <span className="truncate">{account.me.nickname}님</span>
            <motion.span animate={{ rotate: open ? 180 : 0 }} transition={spring} className="flex">
              <CaretDown size={14} />
            </motion.span>
          </button>
          <AnimatePresence>
            {open && (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={spring}
                className="absolute right-0 mt-2 rounded-2xl border border-zinc-200 bg-white p-1"
              >
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false)
                    onOpenSaved()
                  }}
                  className="flex w-full items-center gap-2 whitespace-nowrap rounded-xl px-4 py-2 text-sm text-zinc-700 transition hover:bg-zinc-100 active:scale-[0.98]"
                >
                  <BookmarksSimple size={16} />
                  저장한 결과
                </button>
                <button
                  type="button"
                  onClick={signOut}
                  className="flex w-full items-center gap-2 whitespace-nowrap rounded-xl px-4 py-2 text-sm text-zinc-700 transition hover:bg-zinc-100 active:scale-[0.98]"
                >
                  <SignOut size={16} />
                  로그아웃
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false)
                    setConfirmingDelete(true)
                  }}
                  className="flex w-full items-center gap-2 whitespace-nowrap rounded-xl px-4 py-2 text-sm text-zinc-400 transition hover:bg-zinc-100 hover:text-zinc-700 active:scale-[0.98]"
                >
                  <UserMinus size={16} />
                  회원 탈퇴
                </button>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      )}
      {loginFailed && account.status === 'out' && (
        <p role="alert" className="mt-2 text-sm text-accent">
          로그인하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      {logoutFailed && account.status === 'in' && (
        <p role="alert" className="mt-2 text-sm text-accent">
          로그아웃하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      {deleted && account.status === 'out' && (
        <p role="status" className="mt-2 text-sm text-zinc-600">
          탈퇴했어요. 그동안 고마웠어요.
        </p>
      )}
      {confirmingDelete && (
        <DeleteAccountDialog
          onClose={() => setConfirmingDelete(false)}
          onDeleted={() => {
            setConfirmingDelete(false)
            setDeleted(true)
            setAccount({ status: 'out' })
          }}
        />
      )}
    </div>
  )
}
