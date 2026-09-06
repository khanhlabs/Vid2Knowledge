import { useMutation, useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { workspaceApi } from './api'

const percent = (value: number) =>
  new Intl.NumberFormat('vi-VN', {
    style: 'percent',
    maximumFractionDigits: 1,
  }).format(value)

const score = (value?: number) =>
  value == null ? 'Chưa đủ dữ liệu' : `${value}%`

export function AnalyticsPage() {
  const organizationId = localStorage.getItem('v2k.organizationId') ?? ''
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
