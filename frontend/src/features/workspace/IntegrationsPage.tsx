import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSessionMutation as useMutation } from '../../shared/hooks/useSessionMutation'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../shared/api/client'
import { workspaceApi } from './api'

const WEBHOOK_EVENTS = [
  'AnalysisCompleted',
  'AnalysisFailed',
  'PackagePublished',
  'AssignmentPublished',
  'AssessmentSubmitted',
  'DelayedRecallCompleted',
  'QualityIssueReported',
  'InvitationAccepted',
  'PaymentReceived',
  'SubscriptionCancellationScheduled',
  'SubscriptionPlanChangeScheduled',
  'SubscriptionPlanChangeCancelled',
]

function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 402)
    return 'Tích hợp API/webhook chỉ có trong gói Business đang hoạt động.'
  if (error instanceof ApiError && error.status === 404)
    return 'Tính năng tích hợp chưa được bật cho môi trường này.'
  return error instanceof Error ? error.message : 'Không thể hoàn tất yêu cầu.'
}

export function IntegrationsPage() {
  const organizationId = localStorage.getItem('v2k.organizationId') ?? ''
  const queryClient = useQueryClient()
  const [keyName, setKeyName] = useState('')
  const [keyScopes, setKeyScopes] = useState<string[]>(['analytics:read'])
  const [endpointName, setEndpointName] = useState('')
  const [endpointUrl, setEndpointUrl] = useState('')
  const [events, setEvents] = useState<string[]>(['PackagePublished'])
  const [selectedEndpointId, setSelectedEndpointId] = useState('')
  const [revealedSecret, setRevealedSecret] = useState<{
    title: string
    value: string
  } | null>(null)

  const me = useQuery({ queryKey: ['me'], queryFn: workspaceApi.me })
  const membership = me.data?.organizations.find(
    (item) => item.id === organizationId,
  )
  const canManage = membership?.role === 'OWNER' || membership?.role === 'ADMIN'
  const keys = useQuery({
    queryKey: ['integration-api-keys', organizationId],
    queryFn: () => workspaceApi.integrationApiKeys(organizationId),
    enabled: Boolean(organizationId && canManage),
    retry: false,
  })
  const endpoints = useQuery({
    queryKey: ['webhook-endpoints', organizationId],
    queryFn: () => workspaceApi.webhookEndpoints(organizationId),
    enabled: Boolean(organizationId && canManage),
    retry: false,
  })
  const deliveries = useQuery({
    queryKey: ['webhook-deliveries', organizationId, selectedEndpointId],
    queryFn: () =>
      workspaceApi.webhookDeliveries(organizationId, selectedEndpointId),
    enabled: Boolean(selectedEndpointId),
  })

  const createKey = useMutation({
    mutationFn: () =>
      workspaceApi.createIntegrationApiKey(
        organizationId,
        keyName,
        keyScopes,
        new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
      ),
    onSuccess: async (created) => {
      setRevealedSecret({ title: 'API key mới', value: created.token })
      setKeyName('')
      await queryClient.invalidateQueries({
        queryKey: ['integration-api-keys', organizationId],
      })
    },
  })
  const revokeKey = useMutation({
    mutationFn: (keyId: string) =>
      workspaceApi.revokeIntegrationApiKey(organizationId, keyId),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['integration-api-keys', organizationId],
      }),
  })
  const createEndpoint = useMutation({
    mutationFn: () =>
      workspaceApi.createWebhookEndpoint(
        organizationId,
        endpointName,
        endpointUrl,
        events,
      ),
    onSuccess: async (created) => {
      setRevealedSecret({
        title: `Signing secret · ${created.name}`,
        value: created.signingSecret,
      })
      setEndpointName('')
      setEndpointUrl('')
      await queryClient.invalidateQueries({
        queryKey: ['webhook-endpoints', organizationId],
      })
    },
  })
  const rotateSecret = useMutation({
    mutationFn: (endpointId: string) =>
      workspaceApi.rotateWebhookSecret(organizationId, endpointId),
    onSuccess: async (rotated) => {
      setRevealedSecret({
        title: `Signing secret v${rotated.secretVersion}`,
        value: rotated.signingSecret,
      })
      await queryClient.invalidateQueries({
        queryKey: ['webhook-endpoints', organizationId],
      })
    },
  })
  const disableEndpoint = useMutation({
    mutationFn: (endpointId: string) =>
      workspaceApi.disableWebhookEndpoint(organizationId, endpointId),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['webhook-endpoints', organizationId],
      }),
  })

  if (!organizationId)
    return <div className="screen-message">Hãy chọn tổ chức trước.</div>
  if (me.isPending)
    return <div className="screen-message">Đang kiểm tra quyền tích hợp…</div>
  if (!canManage)
    return (
      <div className="screen-message error">
        Chỉ Owner/Admin được quản lý tích hợp.
      </div>
    )

  const firstError = keys.error ?? endpoints.error
  if (firstError)
    return (
      <div className="screen-message error">
        {errorMessage(firstError)} <Link to="/app">Quay lại workspace</Link>
      </div>
    )

  const submitKey = (event: FormEvent) => {
    event.preventDefault()
    createKey.mutate()
  }
  const submitEndpoint = (event: FormEvent) => {
    event.preventDefault()
    createEndpoint.mutate()
  }

  return (
    <main className="workspace-page integrations-page">
      <header className="app-header">
        <Link to="/app">← Workspace</Link>
        <span>Business integrations</span>
      </header>
      <section className="workspace-heading">
        <div>
          <p className="eyebrow">API · WEBHOOK</p>
          <h1>Kết nối dữ liệu mà không mở cửa tenant.</h1>
          <p>
            Key và signing secret chỉ hiện đúng một lần. Hãy lưu ngay trong
            secret manager của hệ thống nhận.
          </p>
          <a
            className="button-link"
            href="/api/v1/integrations/openapi.json"
            download="vid2knowledge-business-api-v1.json"
          >
            Tải OpenAPI contract v1
          </a>
        </div>
      </section>

      {revealedSecret && (
        <section className="panel secret-reveal" role="alert">
          <strong>{revealedSecret.title} — chỉ hiển thị lần này</strong>
          <code>{revealedSecret.value}</code>
          <button
            type="button"
            onClick={() =>
              void navigator.clipboard.writeText(revealedSecret.value)
            }
          >
            Sao chép
          </button>
          <button
            type="button"
            className="text-button"
            onClick={() => setRevealedSecret(null)}
          >
            Tôi đã lưu an toàn
          </button>
        </section>
      )}

      <div className="integration-grid">
        <section className="panel">
          <p className="eyebrow">API KEYS</p>
          <h2>Quyền truy cập có scope</h2>
          <form onSubmit={submitKey}>
            <label htmlFor="api-key-name">Tên key</label>
            <input
              id="api-key-name"
              value={keyName}
              onChange={(e) => setKeyName(e.target.value)}
              required
              maxLength={120}
            />
            <fieldset>
              <legend>Scopes</legend>
              {['catalog:read', 'analytics:read'].map((scope) => (
                <label key={scope}>
                  <input
                    type="checkbox"
                    checked={keyScopes.includes(scope)}
                    onChange={(e) =>
                      setKeyScopes((current) =>
                        e.target.checked
                          ? [...current, scope]
                          : current.filter((item) => item !== scope),
                      )
                    }
                  />{' '}
                  {scope}
                </label>
              ))}
            </fieldset>
            <button disabled={createKey.isPending || keyScopes.length === 0}>
              Tạo key 30 ngày
            </button>
          </form>
          {createKey.isError && (
            <p className="form-error">{errorMessage(createKey.error)}</p>
          )}
          <div className="integration-list">
            {keys.data?.map((key) => (
              <article key={key.id}>
                <strong>{key.name}</strong>
                <code>{key.tokenPrefix}…</code>
                <small>
                  {key.scopes.join(', ')} · hết hạn{' '}
                  {new Date(key.expiresAt).toLocaleDateString('vi-VN')}
                </small>
                {!key.revokedAt && (
                  <button
                    className="text-button"
                    onClick={() => revokeKey.mutate(key.id)}
                  >
                    Thu hồi
                  </button>
                )}
              </article>
            ))}
          </div>
        </section>

        <section className="panel">
          <p className="eyebrow">SIGNED WEBHOOKS</p>
          <h2>Đẩy sự kiện có retry</h2>
          <form onSubmit={submitEndpoint}>
            <label htmlFor="webhook-name">Tên endpoint</label>
            <input
              id="webhook-name"
              value={endpointName}
              onChange={(e) => setEndpointName(e.target.value)}
              required
              maxLength={120}
            />
            <label htmlFor="webhook-url">Public HTTPS URL</label>
            <input
              id="webhook-url"
              type="url"
              value={endpointUrl}
              onChange={(e) => setEndpointUrl(e.target.value)}
              required
              placeholder="https://example.com/webhooks/vid2knowledge"
            />
            <fieldset className="event-options">
              <legend>Sự kiện</legend>
              {WEBHOOK_EVENTS.map((eventName) => (
                <label key={eventName}>
                  <input
                    type="checkbox"
                    checked={events.includes(eventName)}
                    onChange={(e) =>
                      setEvents((current) =>
                        e.target.checked
                          ? [...current, eventName]
                          : current.filter((item) => item !== eventName),
                      )
                    }
                  />{' '}
                  {eventName}
                </label>
              ))}
            </fieldset>
            <button disabled={createEndpoint.isPending || events.length === 0}>
              Tạo endpoint
            </button>
          </form>
          {createEndpoint.isError && (
            <p className="form-error">{errorMessage(createEndpoint.error)}</p>
          )}
          <div className="integration-list">
            {endpoints.data?.map((endpoint) => (
              <article key={endpoint.id}>
                <strong>{endpoint.name}</strong>
                <code>{endpoint.url}</code>
                <small>
                  {endpoint.eventTypes.join(', ')} · secret v
                  {endpoint.secretVersion} · {endpoint.state}
                </small>
                {endpoint.state === 'ACTIVE' && (
                  <div>
                    <button
                      className="text-button"
                      onClick={() => rotateSecret.mutate(endpoint.id)}
                    >
                      Rotate secret
                    </button>
                    <button
                      className="text-button"
                      onClick={() => disableEndpoint.mutate(endpoint.id)}
                    >
                      Tắt endpoint
                    </button>
                  </div>
                )}
                <button
                  className="text-button"
                  onClick={() => setSelectedEndpointId(endpoint.id)}
                >
                  Xem delivery gần nhất
                </button>
              </article>
            ))}
          </div>
          {selectedEndpointId && (
            <div className="integration-list" aria-label="Webhook deliveries">
              <h3>100 delivery gần nhất</h3>
              {deliveries.isPending && <p>Đang tải delivery log…</p>}
              {deliveries.data?.length === 0 && <p>Chưa có delivery.</p>}
              {deliveries.data?.map((delivery) => (
                <article key={delivery.id}>
                  <strong>
                    {delivery.eventType} · {delivery.state}
                  </strong>
                  <small>
                    attempt {delivery.attemptCount} · HTTP{' '}
                    {delivery.responseStatus ?? '—'} ·{' '}
                    {new Date(delivery.createdAt).toLocaleString('vi-VN')}
                  </small>
                  {delivery.lastError && <span>{delivery.lastError}</span>}
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  )
}
