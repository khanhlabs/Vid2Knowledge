import { useQueryClient } from '@tanstack/react-query'
import { useSessionMutation as useMutation } from '../../shared/hooks/useSessionMutation'
import { useEffect } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../../shared/api/client'

export function AcceptInvitationPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const accept = useMutation({
    mutationFn: () =>
      api('/api/v1/invitations/accept', {
        method: 'POST',
        body: JSON.stringify({ token }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['me'] })
      void navigate('/app', { replace: true })
    },
  })

  useEffect(() => {
    if (token && accept.isIdle) void accept.mutate()
  }, [accept, token])

  if (!token)
    return (
      <div className="screen-message error">
        Link mời không hợp lệ. <Link to="/app">Về workspace</Link>
      </div>
    )
  if (accept.isError)
    return (
      <div className="screen-message error">
        Link mời đã hết hạn, đã được dùng hoặc không khớp email đăng nhập.
      </div>
    )
  return <div className="screen-message">Đang xác nhận lời mời…</div>
}
