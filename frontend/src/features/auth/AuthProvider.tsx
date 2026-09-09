import type { Session } from '@supabase/supabase-js'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { supabase } from '../../shared/lib/supabase'
import { syncRequestIdentity } from '../../shared/api/request-scope'
import { AuthContext, type AuthContextValue, useAuth } from './auth-context'
import { LegalGate } from './LegalGate'
import { syncStoredIdentity } from './identity-storage'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(Boolean(supabase))

  useEffect(() => {
    if (!supabase) return
    let active = true
    let eventVersion = 0
    const applySession = (nextSession: Session | null) => {
      if (!active) return
      syncRequestIdentity(nextSession?.user.id ?? null)
      syncStoredIdentity(nextSession?.user.id ?? null)
      setSession(nextSession)
      setLoading(false)
    }
    const { data } = supabase.auth.onAuthStateChange((_event, nextSession) => {
      eventVersion++
      applySession(nextSession)
    })
    void supabase.auth
      .getSession()
      .then(({ data }) => {
        if (eventVersion === 0) applySession(data.session)
      })
      .catch(() => {
        if (eventVersion === 0) applySession(null)
      })
    return () => {
      active = false
      data.subscription.unsubscribe()
    }
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      loading,
      configured: Boolean(supabase),
      signOut: async () => {
        await supabase?.auth.signOut()
      },
    }),
    [loading, session],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const location = useLocation()
  if (auth.loading)
    return <div className="screen-message">Đang mở phiên làm việc…</div>
  if (!auth.session) {
    return (
      <Navigate
        to="/login"
        state={{ from: `${location.pathname}${location.search}` }}
        replace
      />
    )
  }
  return <LegalGate identityKey={auth.session.user.id}>{children}</LegalGate>
}
