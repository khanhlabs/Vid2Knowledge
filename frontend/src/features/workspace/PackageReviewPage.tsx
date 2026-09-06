import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { workspaceApi } from './api'

const actionsByState: Record<
  string,
  Array<{
    action: 'submit-review' | 'approve' | 'reject' | 'publish' | 'archive'
    label: string
  }>
> = {
  GENERATED: [{ action: 'submit-review', label: 'Gửi kiểm duyệt' }],
  DRAFT: [{ action: 'submit-review', label: 'Gửi kiểm duyệt' }],
  REJECTED: [{ action: 'submit-review', label: 'Gửi lại kiểm duyệt' }],
  IN_REVIEW: [
    { action: 'approve', label: 'Phê duyệt' },
    { action: 'reject', label: 'Yêu cầu chỉnh sửa' },
  ],
  APPROVED: [{ action: 'publish', label: 'Xuất bản' }],
  PUBLISHED: [{ action: 'archive', label: 'Lưu trữ' }],
}

export function PackageReviewPage() {
  const { packageId = '' } = useParams()
  const organizationId = localStorage.getItem('v2k.organizationId') ?? ''
  const queryClient = useQueryClient()
  const [rejectionReason, setRejectionReason] = useState('')
  const result = useQuery({
    queryKey: ['package', organizationId, packageId],
    queryFn: () => workspaceApi.learningPackage(organizationId, packageId),
    enabled: Boolean(organizationId && packageId),
  })
  const transition = useMutation({
    mutationFn: ({
      action,
      reason,
    }: {
      action: 'submit-review' | 'approve' | 'reject' | 'publish' | 'archive'
      reason?: string
    }) =>
      workspaceApi.transitionPackage(organizationId, packageId, action, reason),
    onSuccess: async () => {
      setRejectionReason('')
      await queryClient.invalidateQueries({
        queryKey: ['package', organizationId, packageId],
      })
    },
  })
  const knowledgeIndex = useMutation({
    mutationFn: () => workspaceApi.indexKnowledge(organizationId, packageId),
  })
  if (!organizationId)
    return (
      <div className="screen-message">
        Hãy chọn tổ chức trong workspace trước.
      </div>
    )
  if (result.isPending)
    return <div className="screen-message">Đang tải bản nháp…</div>
  if (result.isError)
    return <div className="screen-message error">Không thể tải học liệu.</div>
  const item = result.data
  return (
    <main className="review-page">
      <div className="review-toolbar">
        <Link to="/app">← Workspace</Link>
        <div>
          <span className="status-pill">{item.state}</span>
          <span>
            {item.verificationState} · bản {item.revisionNo}
          </span>
        </div>
      </div>
      <section className="panel review-content">
        <p className="eyebrow">BẢN NHÁP HỌC LIỆU</p>
        <h1>
          {String(
            (item.content.video as { title?: string } | undefined)?.title ??
              'Học liệu chưa đặt tên',
          )}
        </h1>
        <p>
          Kiểm tra tính chính xác, nguồn dẫn và câu trả lời trước khi xuất bản.
        </p>
        <pre>{JSON.stringify(item.content, null, 2)}</pre>
        {item.state === 'IN_REVIEW' && (
          <label className="review-reason">
            Lý do yêu cầu chỉnh sửa
            <textarea
              value={rejectionReason}
              onChange={(event) => setRejectionReason(event.target.value)}
              minLength={3}
              maxLength={1000}
              placeholder="Nêu chính xác item, timestamp hoặc đáp án cần sửa."
            />
          </label>
        )}
        <div className="review-actions">
          {(actionsByState[item.state] ?? []).map(({ action, label }) => (
            <button
              key={action}
              className={
                action === 'reject' || action === 'archive'
                  ? 'danger-button'
                  : ''
              }
              disabled={
                transition.isPending ||
                (action === 'reject' && rejectionReason.trim().length < 3)
              }
              onClick={() =>
                transition.mutate({
                  action,
                  reason: action === 'reject' ? rejectionReason : undefined,
                })
              }
            >
              {label}
            </button>
          ))}
          {item.state === 'PUBLISHED' && (
            <button
              disabled={knowledgeIndex.isPending}
              onClick={() => knowledgeIndex.mutate()}
            >
              {knowledgeIndex.isPending
                ? 'Đang lập chỉ mục…'
                : 'Bật Ask Video có nguồn'}
            </button>
          )}
        </div>
        {knowledgeIndex.data && (
          <p className="success-message">
            Đã lập chỉ mục {knowledgeIndex.data.chunks} đoạn bằng{' '}
            {knowledgeIndex.data.embeddingModel}.
          </p>
        )}
        {knowledgeIndex.isError && (
          <p className="form-error">
            Không thể lập chỉ mục. Kiểm tra cấu hình AI và thử lại trước khi
            giao tính năng hỏi đáp.
          </p>
        )}
        {transition.isError && (
          <p className="form-error">
            Chuyển trạng thái không hợp lệ hoặc bạn không có quyền.
          </p>
        )}
      </section>
    </main>
  )
}
