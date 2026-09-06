import { Link, useParams } from 'react-router-dom'

const titles: Record<string, string> = {
  terms: 'Điều khoản dịch vụ',
  privacy: 'Chính sách quyền riêng tư',
  'acceptable-use': 'Quy định sử dụng chấp nhận được',
  'ai-notice': 'Thông báo giới hạn của AI',
}

export function LegalDocumentPlaceholder() {
  const { policy = '' } = useParams()
  return (
    <main className="legal-gate">
      <article className="legal-card">
        <p className="eyebrow">DEVELOPMENT PLACEHOLDER</p>
        <h1>{titles[policy] ?? 'Tài liệu pháp lý'}</h1>
        <p className="notice error">
          Đây không phải văn bản pháp lý để phát hành. Production chỉ khởi động
          khi owner cấu hình URL HTTPS của bản đã được tư vấn pháp lý duyệt và
          đặt LEGAL_REVIEWED=true.
        </p>
        <Link to="/">Quay về trang chủ</Link>
      </article>
    </main>
  )
}
