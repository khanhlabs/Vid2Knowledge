import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { type EconomicProfile, workspaceApi } from './api'

const percent = (value: number) =>
  new Intl.NumberFormat('vi-VN', {
    style: 'percent',
    maximumFractionDigits: 1,
  }).format(value)

const score = (value?: number) =>
  value == null ? 'Chưa đủ dữ liệu' : `${value}%`

const money = (value: number) =>
  new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(value)

const profitabilityStatus: Record<string, string> = {
  UNCONFIGURED: 'Chưa xác nhận giả định',
  NO_REVENUE: 'Chưa có doanh thu',
  NEGATIVE: 'Đang lỗ',
  AI_COST_CRITICAL: 'Chi phí AI vượt ngưỡng',
  BELOW_FLOOR: 'Biên lợi nhuận dưới sàn',
  HEALTHY: 'Biên lợi nhuận tốt',
  WATCH: 'Cần theo dõi',
}

function ProfitabilityPanel({ organizationId }: { organizationId: string }) {
  const queryClient = useQueryClient()
  const report = useQuery({
    queryKey: ['profitability', organizationId],
    queryFn: () => workspaceApi.profitability(organizationId),
  })
  const assumptions = useQuery({
    queryKey: ['economic-profile', organizationId],
    queryFn: () => workspaceApi.economicProfile(organizationId),
  })
  const [profileOverride, setProfile] = useState<EconomicProfile | null>(null)
  const profile = profileOverride ?? assumptions.data ?? null
  const [cost, setCost] = useState({
    category: 'OTHER',
    amountVnd: 0,
    note: '',
  })

  const refreshProfitability = () =>
    queryClient.invalidateQueries({
      queryKey: ['profitability', organizationId],
    })
  const saveProfile = useMutation({
    mutationFn: (input: EconomicProfile) =>
      workspaceApi.updateEconomicProfile(organizationId, input),
    onSuccess: (saved) => {
      setProfile(saved)
      queryClient.setQueryData(['economic-profile', organizationId], saved)
      void refreshProfitability()
    },
  })
  const recordCost = useMutation({
    mutationFn: () =>
      workspaceApi.addDirectCost(organizationId, {
        ...cost,
        incurredAt: new Date().toISOString(),
      }),
    onSuccess: () => {
      setCost({ category: 'OTHER', amountVnd: 0, note: '' })
      void refreshProfitability()
    },
  })

  if (report.isPending || assumptions.isPending || !profile) {
    return <section className="panel">Đang tính P&amp;L tài khoản…</section>
  }
  if (report.isError || assumptions.isError) {
    return (
      <section className="panel form-error">
        Không thể tải dữ liệu lợi nhuận. Dữ liệu kết quả học tập vẫn khả dụng.
      </section>
    )
  }

  const data = report.data
  const setNumber = (key: keyof EconomicProfile, value: string) =>
    setProfile({ ...profile, [key]: Number(value) })

  return (
    <section className="panel profitability-panel">
      <div className="section-heading">
        <div>
          <p className="eyebrow">ACCOUNT P&amp;L · 30 NGÀY</p>
          <h2>Lợi nhuận phải được đối soát, không ước đoán màu xanh.</h2>
        </div>
        <span className={`profit-status status-${data.status.toLowerCase()}`}>
          {profitabilityStatus[data.status] ?? data.status}
        </span>
      </div>
      {!data.assumptionsConfirmed && (
        <p className="profit-warning">
          Hệ thống cố ý không kết luận tài khoản có lãi cho đến khi bạn xác nhận
          tỷ giá, phí thanh toán, hạ tầng, hỗ trợ, thuế, CAC và churn bên dưới.
        </p>
      )}
      <div className="metric-grid" aria-label="Kinh tế tài khoản">
        <article>
          <small>Doanh thu ghi nhận</small>
          <strong>{money(data.recognizedRevenueVnd)}</strong>
        </article>
        <article>
          <small>Lợi nhuận đóng góp</small>
          <strong>{money(data.contributionProfitVnd)}</strong>
        </article>
        <article>
          <small>Biên đóng góp</small>
          <strong>
            {data.contributionMargin == null
              ? '—'
              : percent(data.contributionMargin)}
          </strong>
        </article>
        <article>
          <small>LTV / CAC</small>
          <strong>
            {data.ltvCacRatio == null ? '—' : `${data.ltvCacRatio.toFixed(1)}×`}
          </strong>
        </article>
        <article>
          <small>Tiền thu ròng</small>
          <strong>{money(data.netCashVnd)}</strong>
        </article>
        <article>
          <small>Refund</small>
          <strong>{money(data.refundsVnd)}</strong>
        </article>
        <article>
          <small>AI theo giá niêm yết</small>
          <strong>{money(data.shadowAiCostVnd)}</strong>
        </article>
        <article>
          <small>AI / doanh thu</small>
          <strong>
            {data.shadowAiRevenueShare == null
              ? '—'
              : percent(data.shadowAiRevenueShare)}
          </strong>
        </article>
      </div>
      <details className="profit-settings">
        <summary>Giả định kinh tế và chi phí trực tiếp</summary>
        <p>
          Chi phí trực tiếp chỉ dành cho khoản phát sinh ngoài phân bổ hạ tầng
          và hỗ trợ hàng tháng, tránh ghi nhận hai lần.
        </p>
        <form
          className="profit-form"
          onSubmit={(event) => {
            event.preventDefault()
            saveProfile.mutate(profile)
          }}
        >
          {(
            [
              ['usdVndRate', 'Tỷ giá USD/VND'],
              ['paymentFeeBps', 'Phí thanh toán (bps)'],
              ['paymentFixedFeeVnd', 'Phí cố định/giao dịch'],
              ['monthlyInfrastructureVnd', 'Hạ tầng/tháng'],
              ['monthlySupportMinutes', 'Phút hỗ trợ/tháng'],
              ['supportHourlyVnd', 'Chi phí hỗ trợ/giờ'],
              ['taxReserveBps', 'Dự phòng thuế (bps)'],
              ['acquisitionCostVnd', 'CAC tài khoản'],
              ['monthlyLogoChurnBps', 'Logo churn tháng (bps)'],
            ] as const
          ).map(([key, label]) => (
            <label key={key}>
              {label}
              <input
                type="number"
                min="0"
                required
                value={profile[key]}
                onChange={(event) => setNumber(key, event.target.value)}
              />
            </label>
          ))}
          <label className="rights-confirmation profit-confirmation">
            <input
              type="checkbox"
              checked={profile.assumptionsConfirmed}
              onChange={(event) =>
                setProfile({
                  ...profile,
                  assumptionsConfirmed: event.target.checked,
                })
              }
            />
            Tôi đã đối chiếu các giả định với hợp đồng và hóa đơn thực tế.
          </label>
          <button disabled={saveProfile.isPending}>Lưu giả định</button>
          {saveProfile.isError && (
            <p className="form-error">Không thể lưu giả định.</p>
          )}
        </form>
        <form
          className="cost-form"
          onSubmit={(event) => {
            event.preventDefault()
            recordCost.mutate()
          }}
        >
          <h3>Ghi chi phí trực tiếp phát sinh</h3>
          <select
            aria-label="Loại chi phí"
            value={cost.category}
            onChange={(event) =>
              setCost({ ...cost, category: event.target.value })
            }
          >
            <option value="ONBOARDING">Onboarding</option>
            <option value="SUPPORT">Hỗ trợ ngoài định mức</option>
            <option value="INFRASTRUCTURE">Hạ tầng phát sinh</option>
            <option value="STORAGE">Lưu trữ</option>
            <option value="EMAIL">Email</option>
            <option value="SALES">Bán hàng</option>
            <option value="OTHER">Khác</option>
          </select>
          <input
            aria-label="Số tiền chi phí"
            type="number"
            min="1"
            required
            value={cost.amountVnd || ''}
            onChange={(event) =>
              setCost({ ...cost, amountVnd: Number(event.target.value) })
            }
          />
          <input
            aria-label="Ghi chú chi phí"
            minLength={3}
            maxLength={500}
            required
            placeholder="Ví dụ: hỗ trợ triển khai ngoài phạm vi"
            value={cost.note}
            onChange={(event) => setCost({ ...cost, note: event.target.value })}
          />
          <button disabled={recordCost.isPending}>Ghi chi phí</button>
          {recordCost.isError && (
            <p className="form-error">Không thể ghi chi phí.</p>
          )}
        </form>
      </details>
    </section>
  )
}

export function AnalyticsPage() {
  const organizationId = localStorage.getItem('v2k.organizationId') ?? ''
  const me = useQuery({ queryKey: ['me'], queryFn: workspaceApi.me })
  const overview = useQuery({
    queryKey: ['outcome-overview', organizationId],
    queryFn: () => workspaceApi.outcomeOverview(organizationId),
    enabled: Boolean(organizationId),
  })
  const cohorts = useQuery({
    queryKey: ['cohort-outcomes', organizationId],
    queryFn: () => workspaceApi.cohortOutcomes(organizationId),
    enabled: Boolean(organizationId),
  })
  const exportReport = useMutation({
    mutationFn: () => workspaceApi.exportCohortOutcomes(organizationId),
  })

  if (!organizationId) {
    return (
      <div className="screen-message">
        Hãy chọn tổ chức trong workspace trước.
      </div>
    )
  }
  if (overview.isPending || cohorts.isPending) {
    return <div className="screen-message">Đang đối soát dữ liệu kết quả…</div>
  }
  if (overview.isError || cohorts.isError) {
    return (
      <div className="screen-message error">Không thể tải báo cáo kết quả.</div>
    )
  }

  const data = overview.data
  const membership = me.data?.organizations.find(
    (organization) => organization.id === organizationId,
  )
  const canManageProfitability =
    membership?.role === 'OWNER' || membership?.role === 'ADMIN'
  return (
    <main className="workspace-page analytics-page">
      <header className="app-header">
        <Link to="/app/catalog">← Chương trình</Link>
        <button
          className="secondary-button"
          disabled={exportReport.isPending}
          onClick={() => exportReport.mutate()}
        >
          {exportReport.isPending ? 'Đang xuất…' : 'Xuất CSV đối soát'}
        </button>
      </header>
      <section className="workspace-heading">
        <div>
          <p className="eyebrow">BUYER ROI · {data.timezone}</p>
          <h1>Kết quả học tập có thể mang vào buổi gia hạn.</h1>
          <p>
            Activation, completion và delayed recall lấy trực tiếp từ dữ liệu
            server; không trộn với chỉ số YouTube hay sự kiện phía trình duyệt.
          </p>
        </div>
      </section>
      <section className="metric-grid" aria-label="Kết quả toàn tổ chức">
        <article>
          <small>Activation</small>
          <strong>{percent(data.activationRate)}</strong>
        </article>
        <article>
          <small>Hoàn thành</small>
          <strong>{percent(data.completionRate)}</strong>
        </article>
        <article>
          <small>Điểm luyện tập</small>
          <strong>{score(data.practiceAverageScorePercent)}</strong>
        </article>
        <article>
          <small>Nhớ lại sau 3 ngày</small>
          <strong>{score(data.delayedRecallAverageScorePercent)}</strong>
        </article>
        <article>
          <small>Học viên trong cohort</small>
          <strong>{data.learners.toLocaleString('vi-VN')}</strong>
        </article>
        <article>
          <small>Lỗi nội dung đang mở</small>
          <strong>{data.openErrors.toLocaleString('vi-VN')}</strong>
        </article>
      </section>
      {canManageProfitability && (
        <ProfitabilityPanel organizationId={organizationId} />
      )}
      <section className="panel analytics-table-panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">COHORT COMPARISON</p>
            <h2>So sánh hiệu quả vận hành</h2>
          </div>
        </div>
        <div className="table-scroll">
          <table className="analytics-table">
            <thead>
              <tr>
                <th>Cohort</th>
                <th>Học viên</th>
                <th>Activation</th>
                <th>Hoàn thành</th>
                <th>Điểm TB</th>
              </tr>
            </thead>
            <tbody>
              {cohorts.data.map((cohort) => (
                <tr key={cohort.id}>
                  <td>
                    <strong>{cohort.name}</strong>
                    <small>{cohort.status}</small>
                  </td>
                  <td>{cohort.learners}</td>
                  <td>{percent(cohort.activationRate)}</td>
                  <td>{percent(cohort.completionRate)}</td>
                  <td>{score(cohort.averageScorePercent)}</td>
                </tr>
              ))}
              {cohorts.data.length === 0 && (
                <tr>
                  <td colSpan={5}>Chưa có cohort để đối soát.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {exportReport.isError && (
          <p className="form-error">
            Không thể xuất báo cáo. Vui lòng thử lại.
          </p>
        )}
      </section>
    </main>
  )
}
