import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../shared/api/client'
import {
  workspaceApi,
  type LearningPackageContent,
  type LearningPackage,
} from './api'
import { PackageEditor } from './PackageEditor'

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

function localDraft(
  key: string,
  version: number | undefined,
): LearningPackageContent | null {
  if (version == null) return null
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as {
      baseVersion: number
      content: LearningPackageContent
    }
    return parsed.baseVersion === version ? parsed.content : null
  } catch {
    return null
  }
}

export function PackageReviewPage() {
  const { packageId = '' } = useParams()
  const organizationId = localStorage.getItem('v2k.organizationId') ?? ''
  const queryClient = useQueryClient()
  const [rejectionReason, setRejectionReason] = useState('')
  const [draft, setDraft] = useState<LearningPackageContent | null>(null)
  const [dirty, setDirty] = useState(false)
  const result = useQuery({
    queryKey: ['package', organizationId, packageId],
    queryFn: () => workspaceApi.learningPackage(organizationId, packageId),
    enabled: Boolean(organizationId && packageId),
  })
  const me = useQuery({ queryKey: ['me'], queryFn: workspaceApi.me })
  const draftKey = `v2k.package-draft.${organizationId}.${packageId}`
  const storedDraft = localDraft(draftKey, result.data?.version)
  const activeDraft = draft ?? storedDraft ?? result.data?.content ?? null
  const hasUnsavedChanges = dirty || storedDraft != null

  useEffect(() => {
    if (!dirty || !activeDraft || !result.data) return
    const timer = window.setTimeout(() => {
      localStorage.setItem(
        draftKey,
        JSON.stringify({
          baseVersion: result.data.version,
          content: activeDraft,
        }),
      )
    }, 500)
    return () => window.clearTimeout(timer)
  }, [dirty, activeDraft, draftKey, result.data])

  const saveDraft = useMutation({
    mutationFn: () =>
      workspaceApi.savePackageDraft(
        organizationId,
        packageId,
        result.data!.version,
        activeDraft!,
      ),
    onSuccess: (saved) => {
      queryClient.setQueryData<LearningPackage>(
        ['package', organizationId, packageId],
        saved,
      )
      setDraft(saved.content)
      setDirty(false)
      localStorage.removeItem(draftKey)
    },
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
  const exportPackage = useMutation({
    mutationFn: (format: 'markdown' | 'word') =>
      workspaceApi.exportPackage(organizationId, packageId, format),
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
  const membership = me.data?.organizations?.find(
    (organization) => organization.id === organizationId,
  )
  const canEdit =
    ['OWNER', 'ADMIN', 'INSTRUCTOR'].includes(membership?.role ?? '') &&
    ['GENERATED', 'DRAFT', 'REJECTED'].includes(item.state)
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
        <h1>{item.content.video?.title ?? 'Học liệu chưa đặt tên'}</h1>
        <p>
          Kiểm tra tính chính xác, nguồn dẫn và câu trả lời trước khi xuất bản.
        </p>
        {canEdit && activeDraft ? (
          <form
            onSubmit={(event) => {
              event.preventDefault()
              saveDraft.mutate()
            }}
          >
            {storedDraft && !draft && (
              <p className="draft-notice">
                Đã khôi phục thay đổi chưa lưu trên thiết bị này.
              </p>
            )}
            <PackageEditor
              value={activeDraft}
              disabled={saveDraft.isPending}
              onChange={(content) => {
                setDraft(content)
                setDirty(true)
              }}
            />
            <div className="sticky-save-bar">
              <span>
                {hasUnsavedChanges
                  ? 'Thay đổi được giữ cục bộ; chưa tạo revision mới.'
                  : `Revision ${item.revisionNo} đã đồng bộ.`}
              </span>
              <button disabled={!hasUnsavedChanges || saveDraft.isPending}>
                {saveDraft.isPending ? 'Đang lưu…' : 'Lưu revision mới'}
              </button>
            </div>
            {saveDraft.isError && (
              <p className="form-error" role="alert">
                {saveDraft.error instanceof ApiError &&
                saveDraft.error.status === 412
                  ? 'Có người đã lưu phiên bản mới. Thay đổi cục bộ vẫn được giữ; hãy tải lại để đối chiếu trước khi lưu.'
                  : 'Nội dung chưa đạt validation. Kiểm tra số lượng item, timestamp và các trường bắt buộc.'}
              </p>
            )}
          </form>
        ) : (
          <details className="raw-package">
            <summary>Xem JSON revision</summary>
            <pre>{JSON.stringify(item.content, null, 2)}</pre>
          </details>
        )}
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
                hasUnsavedChanges ||
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
        <div className="package-export-actions">
          <div>
            <strong>Mang học liệu vào quy trình của đội ngũ</strong>
            <span>
              Xuất đúng revision đang lưu, gồm đáp án và dẫn chứng timestamp.
            </span>
          </div>
          <button
            className="text-button"
            disabled={hasUnsavedChanges || exportPackage.isPending}
            onClick={() => exportPackage.mutate('markdown')}
          >
            Tải Markdown
          </button>
          <button
            disabled={hasUnsavedChanges || exportPackage.isPending}
            onClick={() => exportPackage.mutate('word')}
          >
            Tải Word · Team
          </button>
        </div>
        {exportPackage.isError && (
          <p className="form-error">
            {exportPackage.error instanceof ApiError &&
            exportPackage.error.status === 402 ? (
              <>
                Xuất Word dành cho Training Team/Business.{' '}
                <Link to="/app#billing">Xem gói phù hợp →</Link>
              </>
            ) : (
              'Không thể tạo file xuất. Vui lòng thử lại.'
            )}
          </p>
        )}
        {hasUnsavedChanges && (
          <p className="draft-notice">
            Lưu revision trước khi chuyển trạng thái kiểm duyệt.
          </p>
        )}
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
