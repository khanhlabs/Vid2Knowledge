import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import logo from '../../assets/logo/full_horizontal.png'
import { ApiError } from '../../shared/api/client'
import { useAuth } from '../auth/auth-context'
import { acquisitionSource } from '../auth/acquisition'
import {
  workspaceApi,
  type AnalysisJob,
  type BillingProfile,
  type BillingQuote,
  type NotificationPreferences,
} from './api'

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    return `${error.message}${error.correlationId ? ` · Mã hỗ trợ ${error.correlationId}` : ''}`
  }
  if (error instanceof Error) return error.message
  return 'Đã có lỗi không mong muốn. Vui lòng thử lại.'
}

function money(value: number) {
  return new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(value)
}

const activationLinks: Record<string, string> = {
  ADD_SOURCE: '/app#create-learning-material',
  REVIEW_PACKAGE: '/app/authoring',
  CREATE_COURSE: '/app/catalog',
  INVITE_LEARNER: '/app#members',
  LAUNCH_PROGRAM: '/app/catalog',
  PROVE_OUTCOME: '/app/analytics',
  REVIEW_OUTCOMES: '/app/analytics',
}

function annualSaving(
  planCode: string,
  amountVnd: number,
  plans: { code: string; amountVnd: number }[],
) {
  if (!planCode.endsWith('_ANNUAL')) return null
  const monthly = plans.find(
    (candidate) => candidate.code === planCode.replace('_ANNUAL', '_MONTHLY'),
  )
  if (!monthly) return null
  return monthly.amountVnd * 12 - amountVnd
}

export function WorkspacePage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const signupSource = acquisitionSource(`?${searchParams.toString()}`)
  const [renderedAt] = useState(() => Date.now())
  const [organizationId, setOrganizationId] = useState(
    () => localStorage.getItem('v2k.organizationId') ?? '',
  )
  const [organizationName, setOrganizationName] = useState('')
  const [youtubeUrl, setYoutubeUrl] = useState('')
  const [sourceMode, setSourceMode] = useState<'YOUTUBE' | 'UPLOAD'>('YOUTUBE')
  const [sourceFile, setSourceFile] = useState<File | null>(null)
  const [uploadProgress, setUploadProgress] = useState(0)
  const [uploadStage, setUploadStage] = useState('')
  const [templateId, setTemplateId] = useState('')
  const [job, setJob] = useState<AnalysisJob | null>(null)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState('LEARNER')
  const [inviteLink, setInviteLink] = useState('')
  const [refundInvoiceId, setRefundInvoiceId] = useState('')
  const [refundReason, setRefundReason] = useState('')
  const [supportReason, setSupportReason] = useState('')
  const [supportTicket, setSupportTicket] = useState('')
  const [supportDuration, setSupportDuration] = useState(60)
  const [promotionCode, setPromotionCode] = useState('')
  const [promotionPlanId, setPromotionPlanId] = useState('')
  const [appliedPromotion, setAppliedPromotion] = useState<{
    planId: string
    quote: BillingQuote
  } | null>(null)
  const [billingProfileEdit, setBillingProfileEdit] = useState<{
    organizationId: string
    profile: Omit<BillingProfile, 'updatedAt'>
  } | null>(null)
  const me = useQuery({ queryKey: ['me'], queryFn: workspaceApi.me })
  const plans = useQuery({ queryKey: ['plans'], queryFn: workspaceApi.plans })
  const deletionRequest = useQuery({
    queryKey: ['privacy-deletion'],
    queryFn: workspaceApi.activeDeletionRequest,
  })
  const notificationPreferences = useQuery({
    queryKey: ['notification-preferences'],
    queryFn: workspaceApi.notificationPreferences,
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
  const activation = useQuery({
    queryKey: ['activation', activeOrganizationId],
    queryFn: () => workspaceApi.activation(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canUseStaffWorkspace),
    refetchInterval: (query) => (query.state.data?.activated ? false : 15_000),
  })
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
  const billingProfile = useQuery({
    queryKey: ['billing-profile', activeOrganizationId],
    queryFn: () => workspaceApi.billingProfile(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && canManageMembers),
  })
  const billingProfileDraft =
    billingProfileEdit?.organizationId === activeOrganizationId
      ? billingProfileEdit.profile
      : billingProfile.isFetched
        ? billingProfile.data
          ? {
              buyerType: billingProfile.data.buyerType,
              legalName: billingProfile.data.legalName,
              taxIdentifier: billingProfile.data.taxIdentifier,
              billingAddress: billingProfile.data.billingAddress,
              billingEmail: billingProfile.data.billingEmail,
              countryCode: 'VN' as const,
              invoiceRequested: billingProfile.data.invoiceRequested,
              version: billingProfile.data.version,
            }
          : {
              buyerType: 'BUSINESS' as const,
              legalName: '',
              taxIdentifier: '',
              billingAddress: '',
              billingEmail: '',
              countryCode: 'VN' as const,
              invoiceRequested: true,
              version: 0,
            }
        : null
  const setBillingProfileDraft = (profile: Omit<BillingProfile, 'updatedAt'>) =>
    setBillingProfileEdit({ organizationId: activeOrganizationId, profile })
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
  const supportGrants = useQuery({
    queryKey: ['support-access-grants', activeOrganizationId],
    queryFn: () => workspaceApi.supportAccessGrants(activeOrganizationId),
    enabled: Boolean(activeOrganizationId && organization?.role === 'OWNER'),
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
        signupSource ?? undefined,
      )
    },
    onSuccess: async (created) => {
      setOrganizationId(created.id)
      setOrganizationName('')
      if (signupSource) {
        const next = new URLSearchParams(searchParams)
        next.delete('source')
        setSearchParams(next, { replace: true })
      }
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
      void queryClient.invalidateQueries({
        queryKey: ['activation', activeOrganizationId],
      })
    },
  })
  const analyzeUpload = useMutation({
    mutationFn: async () => {
      if (!sourceFile) throw new Error('Hãy chọn một file MP4 hoặc WebM.')
      if (!/\.(mp4|webm)$/i.test(sourceFile.name)) {
        throw new Error('Hiện chỉ hỗ trợ file MP4 hoặc WebM.')
      }
      if (sourceFile.size < 1_024 || sourceFile.size > 524_288_000) {
        throw new Error('File phải từ 1 KB đến 500 MiB.')
      }
      setUploadStage('Đang cấp vùng tải lên riêng tư…')
      const reservation = await workspaceApi.reserveSourceUpload(
        activeOrganizationId,
        sourceFile,
      )
      setUploadStage('Đang tải trực tiếp lên kho riêng tư…')
      await workspaceApi.putSourceUpload(
        reservation,
        sourceFile,
        setUploadProgress,
      )
      setUploadStage('Đang xác minh loại và kích thước file…')
      await workspaceApi.completeSourceUpload(
        activeOrganizationId,
        reservation.id,
      )
      setUploadStage('Đang đọc metadata video…')
      await workspaceApi.ingestSourceUpload(
        activeOrganizationId,
        reservation.id,
      )
      for (let attempt = 0; attempt < 240; attempt += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 2_500))
        const upload = (
          await workspaceApi.sourceUploads(activeOrganizationId)
        ).find((item) => item.id === reservation.id)
        if (!upload) throw new Error('Không còn tìm thấy phiên upload.')
        if (upload.state === 'REJECTED' || upload.state === 'EXPIRED') {
          throw new Error(
            `Video không được tiếp nhận: ${upload.failureReason ?? upload.state}`,
          )
        }
        if (upload.state === 'READY' && upload.sourceId) {
          setUploadStage('Đang giữ quota và tạo bản nháp…')
          return workspaceApi.createAnalysis(
            activeOrganizationId,
            upload.sourceId,
            templateId || undefined,
          )
        }
      }
      throw new Error('Xử lý video quá thời gian chờ. Bạn có thể thử lại sau.')
    },
    onSuccess: (created) => {
      setJob(created)
      setSourceFile(null)
      setUploadProgress(0)
      setUploadStage('')
      void queryClient.invalidateQueries({
        queryKey: ['usage', activeOrganizationId],
      })
      void queryClient.invalidateQueries({
        queryKey: ['activation', activeOrganizationId],
      })
    },
  })
  const checkout = useMutation({
    mutationFn: (planId: string) =>
      workspaceApi.checkout(
        activeOrganizationId,
        planId,
        appliedPromotion?.planId === planId
          ? (appliedPromotion.quote.promotionCode ?? undefined)
          : undefined,
      ),
    onSuccess: ({ checkoutUrl }) => window.location.assign(checkoutUrl),
  })
  const validatePromotion = useMutation({
    mutationFn: () =>
      workspaceApi.billingQuote(
        activeOrganizationId,
        promotionPlanId,
        promotionCode,
      ),
    onSuccess: (quote) =>
      setAppliedPromotion({ planId: promotionPlanId, quote }),
  })
  const saveBillingProfile = useMutation({
    mutationFn: () => {
      if (!billingProfileDraft)
        throw new Error('Thông tin xuất hóa đơn chưa sẵn sàng.')
      return workspaceApi.updateBillingProfile(activeOrganizationId, {
        buyerType: billingProfileDraft.buyerType,
        legalName: billingProfileDraft.legalName,
        taxIdentifier: billingProfileDraft.taxIdentifier,
        billingAddress: billingProfileDraft.billingAddress,
        billingEmail: billingProfileDraft.billingEmail,
        countryCode: 'VN',
        invoiceRequested: billingProfileDraft.invoiceRequested,
        expectedVersion: billingProfileDraft.version,
      })
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(['billing-profile', activeOrganizationId], saved)
      setBillingProfileDraft({
        buyerType: saved.buyerType,
        legalName: saved.legalName,
        taxIdentifier: saved.taxIdentifier,
        billingAddress: saved.billingAddress,
        billingEmail: saved.billingEmail,
        countryCode: 'VN',
        invoiceRequested: saved.invoiceRequested,
        version: saved.version,
      })
    },
  })
  const exportInvoices = useMutation({
    mutationFn: () => workspaceApi.exportInvoices(activeOrganizationId),
  })
  const cancelSubscription = useMutation({
    mutationFn: (subscriptionId: string) =>
      workspaceApi.cancelSubscription(activeOrganizationId, subscriptionId),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['subscription', activeOrganizationId],
      }),
  })
  const schedulePlanChange = useMutation({
    mutationFn: (targetPlanId: string) =>
      workspaceApi.schedulePlanChange(
        activeOrganizationId,
        subscription.data!.id,
        targetPlanId,
      ),
    onSuccess: (updated) =>
      queryClient.setQueryData(['subscription', activeOrganizationId], updated),
  })
  const cancelPlanChange = useMutation({
    mutationFn: () =>
      workspaceApi.cancelPlanChange(
        activeOrganizationId,
        subscription.data!.id,
      ),
    onSuccess: (updated) =>
      queryClient.setQueryData(['subscription', activeOrganizationId], updated),
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
      await queryClient.invalidateQueries({
        queryKey: ['activation', activeOrganizationId],
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
  const createSupportGrant = useMutation({
    mutationFn: () =>
      workspaceApi.createSupportAccessGrant(
        activeOrganizationId,
        supportReason,
        supportTicket,
        supportDuration,
      ),
    onSuccess: async () => {
      setSupportReason('')
      setSupportTicket('')
      await queryClient.invalidateQueries({
        queryKey: ['support-access-grants', activeOrganizationId],
      })
    },
  })
  const revokeSupportGrant = useMutation({
    mutationFn: (grantId: string) =>
      workspaceApi.revokeSupportAccessGrant(activeOrganizationId, grantId),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['support-access-grants', activeOrganizationId],
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
  const updateNotificationPreferences = useMutation({
    mutationFn: (preferences: NotificationPreferences) =>
      workspaceApi.updateNotificationPreferences(preferences),
    onSuccess: (saved) =>
      queryClient.setQueryData(['notification-preferences'], saved),
  })

  const submitOrganization = (event: FormEvent) => {
    event.preventDefault()
    createOrganization.mutate()
  }
  const submitAnalysis = (event: FormEvent) => {
    event.preventDefault()
    if (sourceMode === 'UPLOAD') analyzeUpload.mutate()
    else analyze.mutate()
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
            {canManageMembers && <Link to="/app/integrations">Tích hợp</Link>}
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
          {activation.data && canUseStaffWorkspace && (
            <section
              className="panel activation-panel"
              aria-label="Tiến độ kích hoạt"
            >
              <div className="activation-heading">
                <div>
                  <p className="eyebrow">LỘ TRÌNH ĐẾN GIÁ TRỊ THẬT</p>
                  <h2>
                    {activation.data.activated
                      ? 'Workspace đã tạo được outcome đầu tiên'
                      : `${activation.data.completedSteps}/${activation.data.totalSteps} bước đã hoàn thành`}
                  </h2>
                </div>
                <Link
                  className="button-link"
                  to={activationLinks[activation.data.nextAction] ?? '/app'}
                >
                  {activation.data.activated
                    ? 'Xem kết quả'
                    : 'Làm bước tiếp theo'}
                </Link>
              </div>
              <div className="activation-progress" aria-hidden="true">
                <span
                  style={{
                    width: `${(activation.data.completedSteps / activation.data.totalSteps) * 100}%`,
                  }}
                />
              </div>
              <ol className="activation-steps">
                {activation.data.steps.map((step) => (
                  <li
                    className={step.complete ? 'complete' : ''}
                    key={step.code}
                  >
                    <span>{step.complete ? '✓' : '○'}</span>
                    {step.title}
                  </li>
                ))}
              </ol>
              {!activation.data.paid && activation.data.trialEndsAt && (
                <div className="trial-conversion">
                  <span>
                    Trial còn{' '}
                    <strong>
                      {Math.max(
                        0,
                        Math.ceil(
                          (new Date(activation.data.trialEndsAt).getTime() -
                            renderedAt) /
                            86_400_000,
                        ),
                      )}{' '}
                      ngày
                    </strong>
                    . Gói năm tiết kiệm hai tháng và giữ nguyên giá trong cả kỳ.
                  </span>
                  <a href="#billing">Xem gói trả năm →</a>
                </div>
              )}
            </section>
          )}
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
            <section className="panel" id="create-learning-material">
              <p className="eyebrow">TẠO HỌC LIỆU</p>
              <h2>Phân tích video có quyền sử dụng</h2>
              <p>
                Dùng video YouTube công khai hoặc upload video riêng mà tổ chức
                có quyền sử dụng. Thời lượng luôn được kiểm tra phía server
                trước khi giữ quota.
              </p>
              <form onSubmit={submitAnalysis}>
                <label htmlFor="workspace-source-mode">Loại nguồn</label>
                <select
                  id="workspace-source-mode"
                  value={sourceMode}
                  onChange={(event) =>
                    setSourceMode(event.target.value as 'YOUTUBE' | 'UPLOAD')
                  }
                >
                  <option value="YOUTUBE">YouTube công khai</option>
                  <option value="UPLOAD">Video riêng của tổ chức</option>
                </select>
                {sourceMode === 'YOUTUBE' ? (
                  <>
                    <label htmlFor="workspace-youtube-url">URL YouTube</label>
                    <input
                      id="workspace-youtube-url"
                      type="url"
                      value={youtubeUrl}
                      onChange={(event) => setYoutubeUrl(event.target.value)}
                      placeholder="https://www.youtube.com/watch?v=…"
                      required
                    />
                  </>
                ) : (
                  <>
                    <label htmlFor="workspace-source-file">
                      File MP4 hoặc WebM
                    </label>
                    <input
                      id="workspace-source-file"
                      type="file"
                      accept="video/mp4,video/webm,.mp4,.webm"
                      onChange={(event) =>
                        setSourceFile(event.target.files?.[0] ?? null)
                      }
                      required
                    />
                    <p className="fine-print">
                      Tối đa 500 MiB và 4 giờ. Upload riêng tư chỉ có trong gói
                      Training Team hoặc Business.
                    </p>
                    {uploadStage && (
                      <div className="job-status processing" role="status">
                        <strong>{uploadStage}</strong>
                        {uploadProgress > 0 && (
                          <span>{uploadProgress}% đã tải</span>
                        )}
                      </div>
                    )}
                  </>
                )}
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
                <button disabled={analyze.isPending || analyzeUpload.isPending}>
                  {analyze.isPending || analyzeUpload.isPending
                    ? 'Đang kiểm tra nguồn…'
                    : 'Tạo bản nháp có kiểm duyệt'}
                </button>
              </form>
              {(analyze.isError || analyzeUpload.isError) && (
                <p className="form-error">
                  {errorMessage(analyze.error ?? analyzeUpload.error)}
                </p>
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

            <section className="panel plans-panel" id="billing">
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
                      {subscription.data.nextPlanName
                        ? ` · ${subscription.data.nextPlanPaid ? 'đã thanh toán, ' : ''}sẽ chuyển sang ${subscription.data.nextPlanName} cuối kỳ`
                        : subscription.data.cancelAtPeriodEnd
                          ? ' · sẽ dừng cuối kỳ'
                          : ''}
                    </span>
                  </div>
                  {subscription.data.nextPlanName &&
                  !subscription.data.nextPlanPaid ? (
                    <button
                      className="text-button"
                      disabled={cancelPlanChange.isPending}
                      onClick={() => cancelPlanChange.mutate()}
                    >
                      Giữ gói hiện tại
                    </button>
                  ) : !subscription.data.nextPlanName &&
                    !subscription.data.cancelAtPeriodEnd ? (
                    <button
                      className="text-button"
                      disabled={cancelSubscription.isPending}
                      onClick={() =>
                        cancelSubscription.mutate(subscription.data!.id)
                      }
                    >
                      Dừng cuối kỳ
                    </button>
                  ) : null}
                </article>
              )}
              {billingProfileDraft && (
                <form
                  className="billing-profile-form"
                  onSubmit={(event) => {
                    event.preventDefault()
                    saveBillingProfile.mutate()
                  }}
                >
                  <div className="billing-profile-heading">
                    <div>
                      <strong>Thông tin người mua</strong>
                      <small>
                        Được chụp bất biến vào chứng từ khi tạo; sửa sau không
                        làm thay đổi lịch sử.
                      </small>
                    </div>
                    <label className="inline-check">
                      <input
                        type="checkbox"
                        checked={billingProfileDraft.invoiceRequested}
                        onChange={(event) =>
                          setBillingProfileDraft({
                            ...billingProfileDraft,
                            invoiceRequested: event.target.checked,
                          })
                        }
                      />
                      Yêu cầu chứng từ thuế
                    </label>
                  </div>
                  <div className="billing-profile-fields">
                    <label>
                      Loại người mua
                      <select
                        value={billingProfileDraft.buyerType}
                        onChange={(event) =>
                          setBillingProfileDraft({
                            ...billingProfileDraft,
                            buyerType: event.target.value as
                              'BUSINESS' | 'INDIVIDUAL',
                            taxIdentifier:
                              event.target.value === 'INDIVIDUAL'
                                ? ''
                                : billingProfileDraft.taxIdentifier,
                          })
                        }
                      >
                        <option value="BUSINESS">Doanh nghiệp / tổ chức</option>
                        <option value="INDIVIDUAL">Cá nhân</option>
                      </select>
                    </label>
                    <label>
                      {billingProfileDraft.buyerType === 'BUSINESS'
                        ? 'Tên pháp lý'
                        : 'Họ tên'}
                      <input
                        required
                        maxLength={240}
                        value={billingProfileDraft.legalName}
                        onChange={(event) =>
                          setBillingProfileDraft({
                            ...billingProfileDraft,
                            legalName: event.target.value,
                          })
                        }
                      />
                    </label>
                    {billingProfileDraft.buyerType === 'BUSINESS' && (
                      <label>
                        Mã số thuế
                        <input
                          required
                          maxLength={14}
                          inputMode="numeric"
                          placeholder="10 số hoặc 10 số-3 số"
                          value={billingProfileDraft.taxIdentifier ?? ''}
                          onChange={(event) =>
                            setBillingProfileDraft({
                              ...billingProfileDraft,
                              taxIdentifier: event.target.value,
                            })
                          }
                        />
                      </label>
                    )}
                    <label>
                      Email nhận chứng từ
                      <input
                        required
                        type="email"
                        maxLength={320}
                        value={billingProfileDraft.billingEmail}
                        onChange={(event) =>
                          setBillingProfileDraft({
                            ...billingProfileDraft,
                            billingEmail: event.target.value,
                          })
                        }
                      />
                    </label>
                    <label className="billing-address-field">
                      Địa chỉ đăng ký
                      <textarea
                        required
                        maxLength={500}
                        value={billingProfileDraft.billingAddress}
                        onChange={(event) =>
                          setBillingProfileDraft({
                            ...billingProfileDraft,
                            billingAddress: event.target.value,
                          })
                        }
                      />
                    </label>
                  </div>
                  <button
                    className="small-button"
                    disabled={saveBillingProfile.isPending}
                  >
                    {saveBillingProfile.isPending
                      ? 'Đang lưu…'
                      : 'Lưu thông tin người mua'}
                  </button>
                  {saveBillingProfile.isSuccess && (
                    <span className="form-success">Đã lưu phiên bản mới.</span>
                  )}
                  {saveBillingProfile.isError && (
                    <p className="form-error">
                      {errorMessage(saveBillingProfile.error)}
                    </p>
                  )}
                </form>
              )}
              <form
                className="promotion-form"
                onSubmit={(event) => {
                  event.preventDefault()
                  validatePromotion.mutate()
                }}
              >
                <div>
                  <label htmlFor="promotion-code">Mã giới thiệu / ưu đãi</label>
                  <input
                    id="promotion-code"
                    maxLength={40}
                    placeholder="Ví dụ: STUDENT_REF_10"
                    required
                    value={promotionCode}
                    onChange={(event) => {
                      setPromotionCode(event.target.value.toUpperCase())
                      setAppliedPromotion(null)
                    }}
                  />
                </div>
                <div>
                  <label htmlFor="promotion-plan">Áp dụng cho gói</label>
                  <select
                    id="promotion-plan"
                    required
                    value={promotionPlanId}
                    onChange={(event) => {
                      setPromotionPlanId(event.target.value)
                      setAppliedPromotion(null)
                    }}
                  >
                    <option value="">Chọn gói thuê bao</option>
                    {plans.data
                      ?.filter((plan) => plan.productType === 'SUBSCRIPTION')
                      .map((plan) => (
                        <option key={plan.id} value={plan.id}>
                          {plan.name} · {money(plan.amountVnd)}
                        </option>
                      ))}
                  </select>
                </div>
                <button
                  className="small-button"
                  disabled={validatePromotion.isPending}
                >
                  {validatePromotion.isPending ? 'Đang kiểm tra…' : 'Áp dụng'}
                </button>
              </form>
              {validatePromotion.isError && (
                <p className="form-error">
                  {errorMessage(validatePromotion.error)}
                </p>
              )}
              {appliedPromotion && (
                <p className="promotion-success" role="status">
                  Đã áp mã{' '}
                  <strong>{appliedPromotion.quote.promotionCode}</strong>: giảm{' '}
                  {money(appliedPromotion.quote.discountVnd)}. Giá thanh toán
                  được khóa lại ở bước tạo đơn.
                </p>
              )}
              {plans.data?.map((plan) => {
                const saving = annualSaving(
                  plan.code,
                  plan.amountVnd,
                  plans.data,
                )
                const promotionQuote =
                  appliedPromotion?.planId === plan.id
                    ? appliedPromotion.quote
                    : null
                return (
                  <article
                    className={`plan-row ${saving ? 'annual-plan' : ''}`}
                    key={plan.id}
                  >
                    <div>
                      <strong>{plan.name}</strong>
                      {saving ? (
                        <small className="saving-label">
                          Tiết kiệm {money(saving)} so với trả tháng
                        </small>
                      ) : null}
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
                      {promotionQuote ? (
                        <>
                          <span className="original-price">
                            {money(promotionQuote.listPriceVnd)}
                          </span>
                          <b>{money(promotionQuote.amountVnd)}</b>
                        </>
                      ) : (
                        <b>{money(plan.amountVnd)}</b>
                      )}
                      <button
                        className="small-button"
                        disabled={
                          checkout.isPending ||
                          schedulePlanChange.isPending ||
                          (!subscription.data && !billingProfile.data) ||
                          (plan.productType === 'TOP_UP' && !subscription.data)
                        }
                        title={
                          plan.productType === 'TOP_UP' && !subscription.data
                            ? 'Cần có thuê bao đang hoạt động trước khi nạp credit'
                            : !subscription.data && !billingProfile.data
                              ? 'Hãy lưu thông tin người mua trước khi thanh toán'
                              : undefined
                        }
                        onClick={() => {
                          if (
                            subscription.data &&
                            plan.productType === 'SUBSCRIPTION' &&
                            plan.code !== subscription.data.planCode
                          ) {
                            schedulePlanChange.mutate(plan.id)
                          } else {
                            checkout.mutate(plan.id)
                          }
                        }}
                      >
                        {plan.productType === 'TOP_UP'
                          ? 'Nạp credit'
                          : subscription.data &&
                              plan.code !== subscription.data.planCode
                            ? 'Đổi cuối kỳ'
                            : 'Chọn gói'}
                      </button>
                    </div>
                  </article>
                )
              })}
              {checkout.isError && (
                <p className="form-error">{errorMessage(checkout.error)}</p>
              )}
              {schedulePlanChange.isError && (
                <p className="form-error">
                  {errorMessage(schedulePlanChange.error)}
                </p>
              )}
              {cancelPlanChange.isError && (
                <p className="form-error">
                  {errorMessage(cancelPlanChange.error)}
                </p>
              )}
              <p className="fine-print">
                Thanh toán qua PayOS. Quyền lợi chỉ được cấp sau khi webhook đã
                được xác thực. Đổi gói có hiệu lực ở kỳ kế tiếp và chỉ tạo yêu
                cầu thanh toán trong 7 ngày trước ngày gia hạn.
              </p>
              {invoices.data && invoices.data.length > 0 && (
                <div>
                  <div className="invoice-list-heading">
                    <strong>Chứng từ thanh toán gần đây</strong>
                    <button
                      className="text-button"
                      disabled={exportInvoices.isPending}
                      onClick={() => exportInvoices.mutate()}
                    >
                      Xuất CSV cho kế toán
                    </button>
                  </div>
                  {exportInvoices.isError && (
                    <p className="form-error">
                      {errorMessage(exportInvoices.error)}
                    </p>
                  )}
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
                        <span className="invoice-summary">
                          <strong>{money(invoice.amountPaidVnd)}</strong>
                          {invoice.discountVnd > 0 && (
                            <small className="invoice-discount">
                              Giảm {money(invoice.discountVnd)}
                              {invoice.promotionCode
                                ? ` · ${invoice.promotionCode}`
                                : ''}
                            </small>
                          )}
                          {invoice.buyerLegalName && (
                            <small className="invoice-buyer">
                              {invoice.buyerLegalName}
                              {invoice.buyerTaxIdentifier
                                ? ` · MST ${invoice.buyerTaxIdentifier}`
                                : ''}
                              {invoice.taxDocumentRequested
                                ? ' · Đã yêu cầu chứng từ thuế'
                                : ''}
                            </small>
                          )}
                        </span>
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
            <section className="panel member-panel" id="members">
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
          {notificationPreferences.data && (
            <section className="panel notification-preferences-panel">
              <div>
                <p className="eyebrow">THÔNG BÁO</p>
                <h2>Bạn quyết định email nào được gửi</h2>
                <p>
                  Email marketing luôn tắt mặc định. Thay đổi được lưu ngay và
                  có lịch sử consent phía server.
                </p>
              </div>
              <div className="preference-list">
                {(
                  [
                    [
                      'productGuidanceEnabled',
                      'Hướng dẫn kích hoạt và thông báo trial',
                    ],
                    [
                      'assignmentRemindersEnabled',
                      'Nhắc deadline và ôn tập được giao',
                    ],
                    ['marketingEnabled', 'Tin sản phẩm và ưu đãi marketing'],
                  ] as const
                ).map(([key, label]) => (
                  <label key={key}>
                    <input
                      type="checkbox"
                      checked={notificationPreferences.data[key]}
                      disabled={updateNotificationPreferences.isPending}
                      onChange={(event) =>
                        updateNotificationPreferences.mutate({
                          ...notificationPreferences.data,
                          [key]: event.target.checked,
                        })
                      }
                    />
                    {label}
                  </label>
                ))}
              </div>
              {updateNotificationPreferences.isError && (
                <p className="form-error">
                  Không thể lưu lựa chọn thông báo. Vui lòng thử lại.
                </p>
              )}
            </section>
          )}
          {organization?.role === 'OWNER' && (
            <section className="panel support-access-panel">
              <div>
                <p className="eyebrow">HỖ TRỢ AN TOÀN</p>
                <h2>Cấp quyền chẩn đoán có thời hạn</h2>
                <p>
                  Nhân viên hỗ trợ không thể đăng nhập thay bạn hoặc đọc nội
                  dung học. Grant chỉ mở số liệu trạng thái kỹ thuật tối thiểu,
                  hết hạn tối đa sau 24 giờ và mọi lần truy cập đều được audit.
                </p>
              </div>
              <form
                className="stacked-form"
                onSubmit={(event) => {
                  event.preventDefault()
                  createSupportGrant.mutate()
                }}
              >
                <label htmlFor="support-reason">Vấn đề cần hỗ trợ</label>
                <textarea
                  id="support-reason"
                  required
                  maxLength={500}
                  aria-describedby="support-reason-privacy"
                  value={supportReason}
                  onChange={(event) => setSupportReason(event.target.value)}
                />
                <small id="support-reason-privacy">
                  Không nhập tên người học, email, nội dung bài học hoặc dữ liệu
                  cá nhân vào mô tả này. Tối đa 3 quyền được mở cùng lúc.
                </small>
                <div className="support-grant-fields">
                  <label>
                    Mã ticket (nếu có)
                    <input
                      maxLength={120}
                      value={supportTicket}
                      onChange={(event) => setSupportTicket(event.target.value)}
                    />
                  </label>
                  <label>
                    Thời hạn
                    <select
                      value={supportDuration}
                      onChange={(event) =>
                        setSupportDuration(Number(event.target.value))
                      }
                    >
                      <option value={30}>30 phút</option>
                      <option value={60}>1 giờ</option>
                      <option value={240}>4 giờ</option>
                      <option value={1440}>24 giờ</option>
                    </select>
                  </label>
                </div>
                <button disabled={createSupportGrant.isPending}>
                  {createSupportGrant.isPending
                    ? 'Đang cấp…'
                    : 'Cấp quyền chẩn đoán'}
                </button>
                {createSupportGrant.isError && (
                  <p className="form-error">
                    {errorMessage(createSupportGrant.error)}
                  </p>
                )}
              </form>
              {supportGrants.data && supportGrants.data.length > 0 && (
                <div className="member-list">
                  {supportGrants.data.slice(0, 5).map((grant) => (
                    <div key={grant.id}>
                      <span>
                        <strong>
                          {grant.state} · đến{' '}
                          {new Date(grant.expiresAt).toLocaleString('vi-VN')}
                        </strong>
                        <small>
                          {grant.reason}
                          {grant.ticketReference
                            ? ` · ${grant.ticketReference}`
                            : ''}
                        </small>
                      </span>
                      {grant.state === 'ACTIVE' && (
                        <button
                          className="text-button"
                          disabled={revokeSupportGrant.isPending}
                          onClick={() => revokeSupportGrant.mutate(grant.id)}
                        >
                          Thu hồi ngay
                        </button>
                      )}
                    </div>
                  ))}
                </div>
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
