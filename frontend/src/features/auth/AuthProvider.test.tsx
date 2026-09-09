import type { Session } from '@supabase/supabase-js'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthProvider'
import { useAuth } from './auth-context'

const auth = vi.hoisted(() => ({
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
  unsubscribe: vi.fn(),
}))
vi.mock('../../shared/lib/supabase', () => ({ supabase: { auth } }))

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.resetAllMocks()
})

function Probe() {
  const { session, loading } = useAuth()
  return <span>{loading ? 'Loading' : (session?.user.id ?? 'Signed out')}</span>
}

it.each(['SIGNED_OUT', 'SIGNED_IN'])(
  'does not restore a stale initial session after %s',
  async (event) => {
    let resolveInitial!: (value: { data: { session: Session } }) => void
    let notify!: (event: string, session: Session | null) => void
    auth.getSession.mockReturnValue(
      new Promise((resolve) => {
        resolveInitial = resolve
      }),
    )
    auth.onAuthStateChange.mockImplementation((callback: typeof notify) => {
      notify = callback
      return { data: { subscription: { unsubscribe: auth.unsubscribe } } }
    })
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    expect(screen.getByText('Loading')).toBeInTheDocument()
    const nextSession =
      event === 'SIGNED_OUT' ? null : ({ user: { id: 'user-b' } } as Session)
    act(() => notify(event, nextSession))
    await act(async () => {
      resolveInitial({
        data: { session: { user: { id: 'user-a' } } as Session },
      })
      await Promise.resolve()
    })
    expect(
      screen.getByText(nextSession ? 'user-b' : 'Signed out'),
    ).toBeInTheDocument()
    expect(screen.queryByText('user-a')).not.toBeInTheDocument()
    expect(localStorage.getItem('v2k.storageIdentity')).toBe(
      nextSession?.user.id ?? null,
    )
  },
)
