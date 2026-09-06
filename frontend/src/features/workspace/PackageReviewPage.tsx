import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
  const result = useQuery({
    queryKey: ['package', organizationId, packageId],
    queryFn: () => workspaceApi.learningPackage(organizationId, packageId),
    enabled: Boolean(organizationId && packageId),
  })
  const transition = useMutation({
    mutationFn: (
      action: 'submit-review' | 'approve' | 'reject' | 'publish' | 'archive',
    ) => workspaceApi.transitionPackage(organizationId, packageId, action),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['package', organizationId, packageId],
      }),
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
        <div className="review-actions">
          {(actionsByState[item.state] ?? []).map(({ action, label }) => (
            <button
              key={action}
              className={
                action === 'reject' || action === 'archive'
                  ? 'danger-button'
                  : ''
              }
              disabled={transition.isPending}
              onClick={() => transition.mutate(action)}
            >
              {label}
            </button>
          ))}
        </div>
        {transition.isError && (
          <p className="form-error">
            Chuyển trạng thái không hợp lệ hoặc bạn không có quyền.
          </p>
        )}
      </section>
    </main>
  )
}
