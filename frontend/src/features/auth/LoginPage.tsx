import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import logo from '../../assets/logo/full_horizontal.png'
import { supabase } from '../../shared/lib/supabase'
import { useAuth } from './auth-context'

export function LoginPage() {
  const auth = useAuth()
  const [email, setEmail] = useState('')
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState(false)
  if (auth.session) return <Navigate to="/app" replace />

  const magicLink = async (event: FormEvent) => {
    event.preventDefault()
    if (!supabase) return
    setPending(true)
    const { error } = await supabase.auth.signInWithOtp({
      email,
      options: { emailRedirectTo: `${window.location.origin}/app` },
    })
    setPending(false)
    setMessage(
      error
        ? error.message
        : 'Đã gửi liên kết đăng nhập. Hãy kiểm tra email của bạn.',
    )
  }

  const google = async () => {
    if (!supabase) return
    await supabase.auth.signInWithOAuth({
      provider: 'google',
      options: { redirectTo: `${window.location.origin}/app` },
    })
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <img src={logo} alt="Vid2Knowledge" />
        <p className="eyebrow">KHÔNG GIAN ĐÀO TẠO</p>
        <h1>Đăng nhập để tiếp tục.</h1>
        {!auth.configured ? (
          <p className="notice error">
            Chưa cấu hình VITE_SUPABASE_URL và VITE_SUPABASE_ANON_KEY.
          </p>
        ) : (
          <>
            <button className="secondary wide" onClick={() => void google()}>
              Tiếp tục với Google
            </button>
            <div className="divider">hoặc</div>
            <form onSubmit={(event) => void magicLink(event)}>
              <label htmlFor="login-email">Email</label>
              <input
                id="login-email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                required
              />
              <button className="wide" disabled={pending}>
                {pending ? 'Đang gửi…' : 'Gửi magic link'}
              </button>
            </form>
          </>
        )}
        {message && <p className="notice">{message}</p>}
      </section>
    </main>
  )
}
