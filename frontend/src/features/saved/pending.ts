import { saveResult } from './api.ts'
import type { Saved } from './api.ts'

// survives the page leaving for the Kakao login and coming back
const KEY = 'pending-save'

// read before any effect runs: AccountMenu removes the flag from the address once it has shown its notice
const loginFailed = new URLSearchParams(location.search).get('login') === 'failed'

export function stashPending(saved: Saved) {
  sessionStorage.setItem(KEY, JSON.stringify(saved))
}

// saves what was stashed before the login; null when nothing was stashed or the login failed
export function resumePending(): Promise<string> | null {
  const stashed = sessionStorage.getItem(KEY)
  sessionStorage.removeItem(KEY)
  if (!stashed || loginFailed) return null
  return saveResult(JSON.parse(stashed) as Saved)
}
