import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import logo from '../../assets/logo/full_horizontal.png'
import { pilotLeadApi, type PilotLeadRequest } from './api'

const initialLead: PilotLeadRequest = {
  contactName: '',
  workEmail: '',
  organizationName: '',
  buyerRole: 'TRAINING_MANAGER',
  monthlyVideoMinutes: 'BETWEEN_300_599',
  learnerCount: 'BETWEEN_50_199',
  primaryGoal: 'SAVE_AUTHORING_TIME',
  note: '',
  acquisitionSource: 'DIRECT',
  contactConsent: false,
}

function source(value: string | null): PilotLeadRequest['acquisitionSource'] {
  const normalized = value?.trim().toUpperCase()
  return normalized === 'SAMPLE_COURSE' ||
    normalized === 'FOUNDER_OUTREACH' ||
    normalized === 'PARTNER' ||
    normalized === 'REFERRAL'
    ? normalized
    : 'DIRECT'
}

function campaign(value: string | null) {
  const normalized = value?.trim()
  return normalized && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(normalized)
    ? normalized
    : undefined
}

export function PilotPage() {
  const [params] = useSearchParams()
  const [lead, setLead] = useState<PilotLeadRequest>(() => ({
    ...initialLead,
    acquisitionSource: source(params.get('source')),
    acquisitionCampaign: campaign(params.get('campaign')),
  }))
  const [submissionKey, setSubmissionKey] = useState(pilotLeadApi.newKey)
  const [state, setState] = useState<'IDLE' | 'SUBMITTING' | 'SENT' | 'ERROR'>(
    'IDLE',
  )

  const update = <K extends keyof PilotLeadRequest>(
    key: K,
    value: PilotLeadRequest[K],
  ) => setLead((current) => ({ ...current, [key]: value }))

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setState('SUBMITTING')
    try {
      await pilotLeadApi.submit(lead, submissionKey)
      setState('SENT')
      setSubmissionKey(pilotLeadApi.newKey())
    } catch {
      setState('ERROR')
    }
  }

  return (
    <main className="pilot-page">
      <nav>
        <Link to="/">
          <img src={logo} alt="Vid2Knowledge" />
        </Link>
        <Link to="/sample">Xem bài học mẫu</Link>
      </nav>
      <section className="pilot-layout">
        <div className="pilot-offer">
          <p className="eyebrow">PAID PILOT CHO ĐỘI NGŨ ĐÀO TẠO</p>
          <h1>Chứng minh hiệu quả trên video và cohort thật của bạn.</h1>
          <p className="lead">
            Scope khởi điểm 300–600 phút video, một cohort, human QA và báo cáo
            outcome. Ngân sách kiểm chứng 5–15 triệu đồng, chốt theo dữ liệu và
            khối lượng thực tế.
          </p>
          <ul>
            <li>Chỉ dùng nội dung tổ chức có quyền xử lý.</li>
            <li>
              Đo thời gian biên soạn tiết kiệm, completion và kết quả học.
            </li>
            <li>
              Không ép mua subscription nếu pilot không chứng minh được ROI.
            </li>
          </ul>
        </div>
        <form className="pilot-form" onSubmit={(event) => void submit(event)}>
          <h2>Đặt buổi xác định scope</h2>
          <div className="pilot-fields">
            <label>
              Họ tên
              <input
                required
                maxLength={160}
                value={lead.contactName}
                onChange={(event) => update('contactName', event.target.value)}
              />
            </label>
            <label>
              Email công việc
              <input
                required
                type="email"
                maxLength={320}
                value={lead.workEmail}
                onChange={(event) => update('workEmail', event.target.value)}
              />
            </label>
            <label className="wide-field">
              Tổ chức / học viện
              <input
                required
                maxLength={240}
                value={lead.organizationName}
                onChange={(event) =>
                  update('organizationName', event.target.value)
                }
              />
            </label>
            <label>
              Vai trò
              <select
                value={lead.buyerRole}
                onChange={(event) =>
                  update(
                    'buyerRole',
                    event.target.value as PilotLeadRequest['buyerRole'],
                  )
                }
              >
                <option value="OWNER">Chủ doanh nghiệp</option>
                <option value="TRAINING_MANAGER">Quản lý đào tạo</option>
                <option value="OPERATIONS">Vận hành</option>
                <option value="INSTRUCTOR">Giảng viên</option>
                <option value="OTHER">Khác</option>
              </select>
            </label>
            <label>
              Phút video mỗi tháng
              <select
                value={lead.monthlyVideoMinutes}
                onChange={(event) =>
                  update(
                    'monthlyVideoMinutes',
                    event.target
                      .value as PilotLeadRequest['monthlyVideoMinutes'],
                  )
                }
              >
                <option value="UNDER_100">Dưới 100</option>
                <option value="BETWEEN_100_299">100–299</option>
                <option value="BETWEEN_300_599">300–599</option>
                <option value="BETWEEN_600_1499">600–1.499</option>
                <option value="OVER_1500">Từ 1.500</option>
              </select>
            </label>
            <label>
              Học viên mỗi cohort
              <select
                value={lead.learnerCount}
                onChange={(event) =>
                  update(
                    'learnerCount',
                    event.target.value as PilotLeadRequest['learnerCount'],
                  )
                }
              >
                <option value="UNDER_50">Dưới 50</option>
                <option value="BETWEEN_50_199">50–199</option>
                <option value="BETWEEN_200_499">200–499</option>
                <option value="BETWEEN_500_999">500–999</option>
                <option value="OVER_1000">Từ 1.000</option>
              </select>
            </label>
            <label>
              Mục tiêu chính
              <select
                value={lead.primaryGoal}
                onChange={(event) =>
                  update(
                    'primaryGoal',
                    event.target.value as PilotLeadRequest['primaryGoal'],
                  )
                }
              >
                <option value="SAVE_AUTHORING_TIME">
                  Giảm giờ soạn học liệu
                </option>
                <option value="IMPROVE_COMPLETION">
                  Tăng tỷ lệ hoàn thành
                </option>
                <option value="PROVE_LEARNING">Chứng minh kết quả học</option>
                <option value="SCALE_COHORTS">Mở rộng số cohort</option>
                <option value="OTHER">Khác</option>
              </select>
            </label>
            <label className="wide-field">
              Bối cảnh thêm (không nhập dữ liệu cá nhân của học viên)
              <textarea
                maxLength={1000}
                value={lead.note}
                onChange={(event) => update('note', event.target.value)}
              />
            </label>
          </div>
          <label className="inline-check pilot-consent">
            <input
              type="checkbox"
              required
              checked={lead.contactConsent}
              onChange={(event) =>
                update('contactConsent', event.target.checked)
              }
            />
            Tôi đồng ý để Vid2Knowledge liên hệ về yêu cầu pilot này và đã đọc{' '}
            <Link to="/legal/privacy">thông báo quyền riêng tư</Link>.
          </label>
          <button disabled={state === 'SUBMITTING' || state === 'SENT'}>
            {state === 'SUBMITTING'
              ? 'Đang gửi…'
              : state === 'SENT'
                ? 'Đã nhận yêu cầu'
                : 'Gửi yêu cầu pilot'}
          </button>
          {state === 'SENT' && (
            <p className="form-success" role="status">
              Yêu cầu đã được ghi nhận. Chúng tôi sẽ dùng thông tin này để xác
              định scope phù hợp.
            </p>
          )}
          {state === 'ERROR' && (
            <p className="form-error" role="alert">
              Chưa gửi được yêu cầu. Hãy thử lại; hệ thống sẽ không tạo bản
              trùng.
            </p>
          )}
        </form>
      </section>
    </main>
  )
}
