import { useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSessionMutation as useMutation } from '../../shared/hooks/useSessionMutation'
import { legalApi, type LegalPolicy } from './legal-api'

const labels: Record<LegalPolicy['type'], string> = {
  TERMS: 'Điều khoản dịch vụ',
  PRIVACY: 'Chính sách quyền riêng tư',
  ACCEPTABLE_USE: 'Quy định sử dụng chấp nhận được',
  AI_NOTICE: 'Thông báo giới hạn của AI',
}

export function LegalGate({
  children,
  identityKey,
}: {
  children: ReactNode
  identityKey: string
}) {
  const queryClient = useQueryClient()
  const [confirmed, setConfirmed] = useState(false)
  const queryKey = ['legal-status', identityKey]
  const status = useQuery({
    queryKey,
    queryFn: legalApi.status,
  })
  const acceptance = useMutation({
    mutationFn: legalApi.accept,
    onSuccess: (accepted) => queryClient.setQueryData(queryKey, accepted),
  })

  if (status.isPending) {
    return (
      <div className="screen-message">Đang kiểm tra điều khoản hiện hành…</div>
    )
  }
  if (status.isError) {
    return (
      <div className="screen-message error">
        Không thể kiểm tra điều khoản. Quyền truy cập được giữ an toàn; vui lòng
        thử lại.
      </div>
    )
  }
  if (status.data.accepted) return children

  return (
    <main className="legal-gate">
      <section className="legal-card">
        <p className="eyebrow">CẬP NHẬT PHÁP LÝ</p>
        <h1>Đọc và chấp nhận trước khi tiếp tục.</h1>
        <p>
          Bộ chính sách phiên bản {status.data.manifest.policySetVersion} áp
          dụng cho tài khoản này. Mỗi lần nội dung thay đổi, hệ thống sẽ yêu cầu
          xác nhận lại và giữ bằng chứng theo phiên bản.
        </p>
        {!status.data.manifest.reviewed && (
          <p className="notice error">
            Đây là cấu hình phát triển chưa được legal review; production sẽ từ
            chối khởi động với trạng thái này.
          </p>
        )}
        <ul className="legal-policy-list">
          {status.data.manifest.policies.map((policy) => (
            <li key={policy.type}>
              <a href={policy.url} target="_blank" rel="noreferrer">
                {labels[policy.type]}
              </a>
              <small>Phiên bản {policy.version}</small>
            </li>
          ))}
        </ul>
        <label className="rights-confirmation">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(event) => setConfirmed(event.target.checked)}
          />
          Tôi đã mở, đọc và đồng ý với toàn bộ chính sách được liệt kê.
        </label>
        <button
          className="wide"
          disabled={!confirmed || acceptance.isPending}
          onClick={() => acceptance.mutate(status.data)}
        >
          {acceptance.isPending ? 'Đang ghi nhận…' : 'Đồng ý và tiếp tục'}
        </button>
        {acceptance.isError && (
          <p className="form-error">
            Không thể ghi nhận. Có thể policy vừa đổi; hãy tải lại và đọc phiên
            bản mới.
          </p>
        )}
      </section>
    </main>
  )
}
