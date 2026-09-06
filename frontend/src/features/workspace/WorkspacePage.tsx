import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import logo from '../../assets/logo/full_horizontal.png'
import { ApiError } from '../../shared/api/client'
import { useAuth } from '../auth/auth-context'
import { workspaceApi, type AnalysisJob } from './api'

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    return `${error.message}${error.correlationId ? ` · Mã hỗ trợ ${error.correlationId}` : ''}`
  }
  return 'Đã có lỗi không mong muốn. Vui lòng thử lại.'
}

function money(value: number) {
  return new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(value)
}

export function WorkspacePage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [organizationId, setOrganizationId] = useState(
    () => localStorage.getItem('v2k.organizationId') ?? '',
  )
  const [organizationName, setOrganizationName] = useState('')
  const [youtubeUrl, setYoutubeUrl] = useState('')
  const [templateId, setTemplateId] = useState('')
  const [job, setJob] = useState<AnalysisJob | null>(null)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState('LEARNER')
  const [inviteLink, setInviteLink] = useState('')
  const [refundInvoiceId, setRefundInvoiceId] = useState('')
  const [refundReason, setRefundReason] = useState('')
  const me = useQuery({ queryKey: ['me'], queryFn: workspaceApi.me })
  const plans = useQuery({ queryKey: ['plans'], queryFn: workspaceApi.plans })
  const deletionRequest = useQuery({
    queryKey: ['privacy-deletion'],
    queryFn: workspaceApi.activeDeletionRequest,
  })

  const activeOrganizationId =
    organizationId || me.data?.organizations[0]?.id || ''

  useEffect(() => {
    if (activeOrganizationId)
      localStorage.setItem('v2k.organizationId', activeOrganizationId)
  }, [activeOrganizationId])

  const organization = useMemo(
    () =>
      me.data?.organizations.find((item) => item.id === activeOrganizationId),
    [activeOrganizationId, me.data],
  )
  const usage = useQuery({
    queryKey: ['usage', activeOrganizationId],
    queryFn: () => workspaceApi.usage(activeOrganizationId),
    enabled: Boolean(activeOrganizationId),
  })
  const canManageMembers =
    organization?.role === 'OWNER' || organization?.role === 'ADMIN'
  const canAuthor =
    organization?.role === 'OWNER' ||
    organization?.role === 'ADMIN' ||
    organization?.role === 'INSTRUCTOR'
  const canUseStaffWorkspace = organization?.role !== 'LEARNER'
  const templates = useQuery({
    queryKey: ['content-templates', activeOrganizationId],
    queryFn: () => workspaceApi.templates(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canAuthor),
  })
  const subscription = useQuery({
    queryKey: ['subscription', activeOrganizationId],
    queryFn: () => workspaceApi.subscription(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canManageMembers),
  })
  const invoices = useQuery({
    queryKey: ['invoices', activeOrganizationId],
    queryFn: () => workspaceApi.invoices(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canManageMembers),
  })
  const refunds = useQuery({
    queryKey: ['refunds', activeOrganizationId],
    queryFn: () => workspaceApi.refunds(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canManageMembers),
  })
  const members = useQuery({
    queryKey: ['members', activeOrganizationId],
    queryFn: () => workspaceApi.members(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canManageMembers),
  })
  const invitations = useQuery({
    queryKey: ['invitations', activeOrganizationId],
    queryFn: () => workspaceApi.invitations(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canManageMembers),
  })
  const jobQuery = useQuery({
    queryKey: ['analysis-job', activeOrganizationId, job?.id],
    queryFn: () => workspaceApi.job(activeOrganizationId, job!.id),
    enabled: Boolean(activeOrganizationId && job?.id),
    refetchInterval: (query) => {
      const state = query.state.data?.state
      return state === 'COMPLETED' || state === 'FAILED' ? false : 2_500
    },
  })
  const currentJob = jobQuery.data ?? job

  const createOrganization = useMutation({
    mutationFn: async () => {
      const slug = organizationName
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-|-$/g, '')
      return workspaceApi.createOrganization(
        organizationName,
        `${slug}-${Date.now().toString(36)}`,
      )
    },
    onSuccess: async (created) => {
      setOrganizationId(created.id)
      setOrganizationName('')
      await queryClient.invalidateQueries({ queryKey: ['me'] })
    },
  })
  const analyze = useMutation({
    mutationFn: async () => {
      const source = await workspaceApi.registerSource(
        activeOrganizationId,
        youtubeUrl,
      )
      return workspaceApi.createAnalysis(
        activeOrganizationId,
        source.id,
        templateId || undefined,
      )
    },
    onSuccess: (created) => {
      setJob(created)
      void queryClient.invalidateQueries({
        queryKey: ['usage', activeOrganizationId],
      })
    },
  })
  const checkout = useMutation({
    mutationFn: (planId: string) =>
      workspaceApi.checkout(activeOrganizationId, planId),
    onSuccess: ({ checkoutUrl }) => window.location.assign(checkoutUrl),
  })
  const cancelSubscription = useMutation({
    mutationFn: (subscriptionId: string) =>
      workspaceApi.cancelSubscription(activeOrganizationId, subscriptionId),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['subscription', activeOrganizationId],
      }),
  })
  const requestRefund = useMutation({
    mutationFn: () =>
      workspaceApi.requestRefund(
        activeOrganizationId,
        refundInvoiceId,
        refundReason,
      ),
    onSuccess: async () => {
      setRefundInvoiceId('')
      setRefundReason('')
      await queryClient.invalidateQueries({
        queryKey: ['refunds', activeOrganizationId],
      })
    },
  })
  const invite = useMutation({
    mutationFn: () =>
      workspaceApi.invite(activeOrganizationId, inviteEmail, inviteRole),
    onSuccess: async (created) => {
      setInviteEmail('')
      setInviteLink(
        `${window.location.origin}/accept-invitation?token=${encodeURIComponent(created.token)}`,
      )
      await queryClient.invalidateQueries({
        queryKey: ['invitations', activeOrganizationId],
      })
    },
  })
  const revokeInvitation = useMutation({
    mutationFn: (id: string) =>
      workspaceApi.revokeInvitation(activeOrganizationId, id),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['invitations', activeOrganizationId],
      }),
  })
  const exportPersonalData = useMutation({
    mutationFn: workspaceApi.exportPersonalData,
  })
  const requestAccountDeletion = useMutation({
    mutationFn: workspaceApi.requestAccountDeletion,
    onSuccess: async () =>
      queryClient.invalidateQueries({ queryKey: ['privacy-deletion'] }),
  })
  const cancelAccountDeletion = useMutation({
    mutationFn: workspaceApi.cancelAccountDeletion,
    onSuccess: async () =>
      queryClient.invalidateQueries({ queryKey: ['privacy-deletion'] }),
  })

  const submitOrganization = (event: FormEvent) => {
    event.preventDefault()
    createOrganization.mutate()
  }
  const submitAnalysis = (event: FormEvent) => {
    event.preventDefault()
    analyze.mutate()
  }

  if (me.isPending)
    return <div className="screen-message">Đang tải workspace…</div>
  if (me.isError)
    return <div className="screen-message error">{errorMessage(me.error)}</div>

  return (
    <main className="workspace-page">
      <header className="app-header">
        <Link to="/">
          <img src={logo} alt="Vid2Knowledge" />
        </Link>
        <div className="header-account">
          <span>{me.data.email}</span>
          <button className="text-button" onClick={() => void auth.signOut()}>
            Đăng xuất
          </button>
        </div>
      </header>

      <section className="workspace-heading">
        <div>
          <p className="eyebrow">TRUNG TÂM VẬN HÀNH</p>
          <h1>Biến nội dung thành kết quả.</h1>
        </div>
        {me.data.organizations.length > 0 && (
          <label className="organization-picker">
            Tổ chức
            <select
              value={activeOrganizationId}
              onChange={(event) => setOrganizationId(event.target.value)}
            >
              {me.data.organizations.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name} · {item.role}
                </option>
              ))}
            </select>
          </label>
        )}
        {organization && canUseStaffWorkspace && (
          <div className="workspace-links">
            <Link to="/app/authoring">Studio & kiểm duyệt</Link>
            <Link to="/app/catalog">Chương trình</Link>
          </div>
        )}
      </section>

      {!organization ? (
        <section className="panel onboarding-panel">
          <p className="eyebrow">BƯỚC ĐẦU TIÊN</p>
          <h2>Tạo không gian cho đội ngũ</h2>
          <p>Mỗi tổ chức có dữ liệu, quota, thành viên và hóa đơn tách biệt.</p>
          <form onSubmit={submitOrganization}>
            <label htmlFor="organization-name">Tên tổ chức</label>
            <div className="input-row">
              <input
                id="organization-name"
                value={organizationName}
                onChange={(event) => setOrganizationName(event.target.value)}
                required
                minLength={2}
                maxLength={120}
              />
              <button disabled={createOrganization.isPending}>
                Tạo workspace
              </button>
            </div>
          </form>
          {createOrganization.isError && (
            <p className="form-error">
              {errorMessage(createOrganization.error)}
            </p>
          )}
        </section>
      ) : (
        <>
          <section className="metric-grid" aria-label="Tổng quan sử dụng">
            <article>
              <small>Quota còn lại</small>
              <strong>
                {usage.data
                  ? Math.floor(usage.data.availableSeconds / 60).toLocaleString(
                      'vi-VN',
                    )
                  : '—'}{' '}
                phút
              </strong>
            </article>
            <article>
              <small>Đã xử lý kỳ này</small>
              <strong>
                {usage.data
                  ? Math.ceil(usage.data.committedSeconds / 60).toLocaleString(
                      'vi-VN',
                    )
                  : '—'}{' '}
                phút
              </strong>
            </article>
            <article>
              <small>Ask Video còn lại</small>
              <strong>
                {usage.data
                  ? usage.data.availableQaQueries.toLocaleString('vi-VN')
                  : '—'}{' '}
                câu hỏi
              </strong>
            </article>
            <article>
              <small>Chi phí AI thực tế</small>
              <strong>
                {usage.data
                  ? `$${(usage.data.actualAiCostMicrousd / 1_000_000).toFixed(3)}`
                  : '—'}
              </strong>
            </article>
            <article>
              <small>Biên an toàn dự phóng</small>
              <strong>
                {usage.data
                  ? `$${(usage.data.shadowAiCostMicrousd / 1_000_000).toFixed(3)}`
                  : '—'}
              </strong>
            </article>
          </section>

          <div className="workspace-grid">
            <section className="panel">
              <p className="eyebrow">TẠO HỌC LIỆU</p>
              <h2>Phân tích video có quyền sử dụng</h2>
              <p>
                Dán video YouTube công khai của tổ chức. Thời lượng được kiểm
                tra phía server trước khi giữ quota.
              </p>
              <form onSubmit={submitAnalysis}>
                <label htmlFor="workspace-youtube-url">URL YouTube</label>
                <input
                  id="workspace-youtube-url"
                  type="url"
                  value={youtubeUrl}
                  onChange={(event) => setYoutubeUrl(event.target.value)}
                  placeholder="https://www.youtube.com/watch?v=…"
                  required
                />
                <label htmlFor="workspace-template">Template đầu ra</label>
                <select
                  id="workspace-template"
                  value={templateId}
                  onChange={(event) => setTemplateId(event.target.value)}
                >
                  <option value="">Mặc định · tự nhận diện ngôn ngữ</option>
                  {templates.data
                    ?.filter((template) => template.state === 'ACTIVE')
                    .map((template) => (
                      <option key={template.id} value={template.id}>
                        {template.name}
                      </option>
                    ))}
                </select>
                <label className="rights-confirmation">
                  <input type="checkbox" required /> Tôi xác nhận tổ chức có
                  quyền dùng video này để tạo học liệu.
                </label>
                <button disabled={analyze.isPending}>
                  {analyze.isPending
                    ? 'Đang kiểm tra nguồn…'
                    : 'Tạo bản nháp có kiểm duyệt'}
                </button>
              </form>
              {analyze.isError && (
                <p className="form-error">{errorMessage(analyze.error)}</p>
              )}
              {currentJob && (
                <div className={`job-status ${currentJob.state.toLowerCase()}`}>
                  <strong>Job {currentJob.state}</strong>
                  <span>Lần xử lý {currentJob.attempt}</span>
                  {currentJob.packageId && (
                    <Link to={`/app/packages/${currentJob.packageId}`}>
                      Mở bản nháp để kiểm duyệt →
                    </Link>
                  )}
                </div>
              )}
            </section>

            <section className="panel plans-panel">
              <p className="eyebrow">NÂNG CẤP</p>
              <h2>Mua quota theo nhu cầu thật</h2>
              {subscription.data && (
                <article className="plan-row current-subscription">
                  <div>
                    <strong>{subscription.data.planName} đang hoạt động</strong>
                    <span>
                      Dùng đến{' '}
                      {new Date(subscription.data.periodEnd).toLocaleDateString(
                        'vi-VN',
                      )}
                      {subscription.data.cancelAtPeriodEnd
                        ? ' · sẽ dừng cuối kỳ'
                        : ''}
                    </span>
                  </div>
                  {!subscription.data.cancelAtPeriodEnd && (
                    <button
                      className="text-button"
                      disabled={cancelSubscription.isPending}
                      onClick={() =>
                        cancelSubscription.mutate(subscription.data!.id)
                      }
                    >
                      Dừng cuối kỳ
                    </button>
                  )}
                </article>
              )}
              {plans.data?.map((plan) => (
                <article className="plan-row" key={plan.id}>
                  <div>
                    <strong>{plan.name}</strong>
                    <span>
                      {Math.floor(
                        plan.processedVideoSeconds / 60,
                      ).toLocaleString('vi-VN')}{' '}
                      phút ·{' '}
                      {new Intl.NumberFormat('vi-VN').format(plan.qaQueries)}{' '}
                      lượt hỏi AI ·{' '}
                      {plan.productType === 'TOP_UP'
                        ? 'credit dùng đến cuối kỳ hiện tại'
                        : plan.interval === 'YEAR'
                          ? 'năm'
                          : 'tháng'}
                    </span>
                  </div>
                  <div>
                    <b>{money(plan.amountVnd)}</b>
                    <button
                      className="small-button"
                      disabled={
                        checkout.isPending ||
                        (plan.productType === 'TOP_UP' && !subscription.data)
                      }
                      title={
                        plan.productType === 'TOP_UP' && !subscription.data
                          ? 'Cần có thuê bao đang hoạt động trước khi nạp credit'
                          : undefined
                      }
                      onClick={() => checkout.mutate(plan.id)}
                    >
                      {plan.productType === 'TOP_UP'
                        ? 'Nạp credit'
                        : 'Chọn gói'}
                    </button>
                  </div>
                </article>
              ))}
              {checkout.isError && (
                <p className="form-error">{errorMessage(checkout.error)}</p>
              )}
              <p className="fine-print">
                Thanh toán qua PayOS. Quyền lợi chỉ được cấp sau khi webhook đã
                được xác thực.
              </p>
              {invoices.data && invoices.data.length > 0 && (
                <div className="member-list" aria-label="Hóa đơn gần đây">
                  {invoices.data.slice(0, 5).map((invoice) => (
                    <div key={invoice.id}>
                      <span>
                        <strong>{invoice.invoiceNumber}</strong>
                        <small>
                          {new Date(invoice.createdAt).toLocaleDateString(
                            'vi-VN',
                          )}{' '}
                          · {invoice.state}
                        </small>
                      </span>
                      <span>{money(invoice.amountPaidVnd)}</span>
                      {invoice.invoiceType === 'TOP_UP' &&
                        invoice.state === 'PAID' &&
                        !refunds.data?.some(
                          (refund) => refund.invoiceId === invoice.id,
                        ) && (
                          <button
                            className="text-button"
                            onClick={() => setRefundInvoiceId(invoice.id)}
                          >
                            Yêu cầu hoàn tiền
                          </button>
                        )}
                    </div>
                  ))}
                </div>
              )}
              {refundInvoiceId && (
                <form
                  className="stacked-form"
                  onSubmit={(event) => {
                    event.preventDefault()
                    requestRefund.mutate()
                  }}
                >
                  <label htmlFor="refund-reason">
                    Lý do hoàn credit chưa sử dụng
                  </label>
                  <textarea
                    id="refund-reason"
                    minLength={10}
                    maxLength={1000}
                    required
                    value={refundReason}
                    onChange={(event) => setRefundReason(event.target.value)}
                  />
                  <div>
                    <button
                      className="small-button"
                      disabled={requestRefund.isPending}
                    >
                      Gửi yêu cầu
                    </button>{' '}
                    <button
                      type="button"
                      className="text-button"
                      onClick={() => setRefundInvoiceId('')}
                    >
                      Đóng
                    </button>
                  </div>
                </form>
              )}
              {requestRefund.isError && (
                <p className="form-error">
                  {errorMessage(requestRefund.error)}
                </p>
              )}
              {refunds.data?.map((refund) => (
                <p className="fine-print" key={refund.id}>
                  Hoàn tiền {money(refund.amountVnd)} ·{' '}
                  {refund.state === 'REQUESTED'
                    ? 'đang chờ đối soát ngân hàng'
                    : refund.state === 'SUCCEEDED'
                      ? `đã hoàn · ${refund.providerReference}`
                      : 'không được chấp thuận'}
                </p>
              ))}
              {cancelSubscription.isError && (
                <p className="form-error">
                  Không thể cập nhật gia hạn. Vui lòng thử lại.
                </p>
              )}
            </section>
          </div>
          <p className="learner-shortcut">
            <Link to="/app/catalog">Điều phối khóa học & cohort</Link>
            {' · '}
            Đang học trong tổ chức này?{' '}
            <Link to={`/learn/${activeOrganizationId}`}>Mở cổng học viên</Link>
          </p>
          {canManageMembers && (
            <section className="panel member-panel">
              <div>
                <p className="eyebrow">ĐỘI NGŨ & HỌC VIÊN</p>
                <h2>Mời đúng người, đúng quyền</h2>
                <p>
                  Link mời gắn với đúng email, hết hạn và chỉ dùng được một lần.
                </p>
              </div>
              <form
                onSubmit={(event) => {
                  event.preventDefault()
                  invite.mutate()
                }}
              >
                <label htmlFor="invite-email">Email người nhận</label>
                <div className="invite-row">
                  <input
                    id="invite-email"
                    type="email"
                    value={inviteEmail}
                    onChange={(event) => setInviteEmail(event.target.value)}
                    required
                  />
                  <select
                    aria-label="Vai trò"
                    value={inviteRole}
                    onChange={(event) => setInviteRole(event.target.value)}
                  >
                    <option value="LEARNER">Học viên</option>
                    <option value="INSTRUCTOR">Giảng viên</option>
                    <option value="REVIEWER">Kiểm duyệt</option>
                    <option value="ADMIN">Quản trị</option>
                  </select>
                  <button disabled={invite.isPending}>Tạo link mời</button>
                </div>
                {inviteLink && (
                  <div className="invite-link">
                    <span>{inviteLink}</span>
                    <button
                      type="button"
                      className="small-button"
                      onClick={() =>
                        void navigator.clipboard.writeText(inviteLink)
                      }
                    >
                      Sao chép
                    </button>
                  </div>
                )}
              </form>
              <div className="member-list">
                {members.data?.map((member) => (
                  <div key={member.id}>
                    <span>
                      <strong>{member.displayName}</strong>
                      <small>{member.email}</small>
                    </span>
                    <span>
                      {member.role} · {member.status}
                    </span>
                  </div>
                ))}
                {invitations.data
                  ?.filter((item) => item.state === 'PENDING')
                  .map((item) => (
                    <div key={item.id}>
                      <span>
                        <strong>{item.email}</strong>
                        <small>Lời mời đang chờ · {item.role}</small>
                      </span>
                      <button
                        className="text-button"
                        disabled={revokeInvitation.isPending}
                        onClick={() => revokeInvitation.mutate(item.id)}
                      >
                        Thu hồi
                      </button>
                    </div>
                  ))}
              </div>
              {(invite.isError || revokeInvitation.isError) && (
                <p className="form-error">
                  Không thể cập nhật lời mời. Vui lòng thử lại.
                </p>
              )}
            </section>
          )}
          <section className="panel member-panel">
            <div>
              <p className="eyebrow">DỮ LIỆU CÁ NHÂN</p>
              <h2>Xuất hoặc xóa tài khoản</h2>
              <p>
                Bản xuất JSON chỉ chứa dữ liệu gắn với tài khoản của bạn. Yêu
                cầu xóa có thời gian chờ 7 ngày và cần chuyển quyền sở hữu tổ
                chức trước.
              </p>
            </div>
            <div className="invite-row">
              <button
                className="small-button"
                disabled={exportPersonalData.isPending}
                onClick={() => exportPersonalData.mutate()}
              >
                Tải dữ liệu của tôi
              </button>
              {deletionRequest.data ? (
                <button
                  className="text-button"
                  disabled={cancelAccountDeletion.isPending}
                  onClick={() => cancelAccountDeletion.mutate()}
                >
                  Hủy yêu cầu xóa (dự kiến{' '}
                  {new Date(
                    deletionRequest.data.scheduledFor,
                  ).toLocaleDateString('vi-VN')}
                  )
                </button>
              ) : (
                <button
                  className="text-button"
                  disabled={requestAccountDeletion.isPending}
                  onClick={() => {
                    if (
                      window.confirm(
                        'Lên lịch xóa tài khoản sau 7 ngày? Bạn có thể hủy trong thời gian chờ.',
                      )
                    )
                      requestAccountDeletion.mutate()
                  }}
                >
                  Yêu cầu xóa tài khoản
                </button>
              )}
            </div>
            {(exportPersonalData.isError ||
              requestAccountDeletion.isError ||
              cancelAccountDeletion.isError) && (
              <p className="form-error">
                {errorMessage(
                  exportPersonalData.error ??
                    requestAccountDeletion.error ??
                    cancelAccountDeletion.error,
                )}
              </p>
            )}
          </section>
        </>
      )}
    </main>
  )
}
