import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSessionMutation as useMutation } from '../../shared/hooks/useSessionMutation'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { workspaceApi, type ContentTemplate } from './api'

const initialProfile: ContentTemplate['outputProfile'] = {
  language: 'vi',
  audience: 'employee',
  difficulty: 'intermediate',
  flashcards: 12,
  quizQuestions: 6,
  tone: 'concise',
}

export function AuthoringPage() {
  const organizationId = localStorage.getItem('v2k.organizationId') ?? ''
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [profile, setProfile] = useState(initialProfile)
  const me = useQuery({ queryKey: ['me'], queryFn: workspaceApi.me })
  const membership = me.data?.organizations.find(
    (item) => item.id === organizationId,
  )
  const canCreate =
    membership?.role === 'OWNER' ||
    membership?.role === 'ADMIN' ||
    membership?.role === 'INSTRUCTOR'
  const canReview =
    membership?.role === 'OWNER' ||
    membership?.role === 'ADMIN' ||
    membership?.role === 'REVIEWER'
  const canManageSettings =
    membership?.role === 'OWNER' || membership?.role === 'ADMIN'
  const templates = useQuery({
    queryKey: ['content-templates', organizationId],
    queryFn: () => workspaceApi.templates(organizationId),
    enabled: Boolean(organizationId && membership),
  })
  const reviewQueue = useQuery({
    queryKey: ['review-queue', organizationId],
    queryFn: () => workspaceApi.reviewQueue(organizationId),
    enabled: Boolean(organizationId && canReview),
  })
  const questionBank = useQuery({
    queryKey: ['question-bank', organizationId],
    queryFn: () => workspaceApi.questionBank(organizationId),
    enabled: Boolean(organizationId && membership),
  })
  const settings = useQuery({
    queryKey: ['authoring-settings', organizationId],
    queryFn: () => workspaceApi.authoringSettings(organizationId),
    enabled: Boolean(organizationId && membership),
  })
  const create = useMutation({
    mutationFn: () =>
      workspaceApi.createTemplate(organizationId, name, profile),
    onSuccess: async () => {
      setName('')
      await queryClient.invalidateQueries({
        queryKey: ['content-templates', organizationId],
      })
    },
  })
  const updateSettings = useMutation({
    mutationFn: (approvalRequired: boolean) =>
      workspaceApi.updateAuthoringSettings(organizationId, approvalRequired),
    onSuccess: async () =>
      queryClient.invalidateQueries({
        queryKey: ['authoring-settings', organizationId],
      }),
  })

  if (!organizationId) {
    return (
      <div className="screen-message">
        Hãy chọn tổ chức trong workspace trước.
      </div>
    )
  }
  if (me.isPending) {
    return <div className="screen-message">Đang kiểm tra quyền authoring…</div>
  }
  if (!membership) {
    return (
      <div className="screen-message error">Bạn không thuộc tổ chức này.</div>
    )
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    create.mutate()
  }

  return (
    <main className="workspace-page authoring-page">
      <header className="app-header">
        <Link to="/app">← Workspace</Link>
        <Link to="/app/catalog">Điều phối chương trình →</Link>
      </header>
      <section className="workspace-heading">
        <div>
          <p className="eyebrow">AUTHORING OPERATIONS</p>
          <h1>Chuẩn hóa đầu ra, kiểm duyệt có trách nhiệm.</h1>
          <p>
            Template được validate phía server; mọi quyết định duyệt/từ chối gắn
            đúng revision và người thực hiện.
          </p>
        </div>
      </section>
      <div className="workspace-grid">
        <section className="panel">
          <h2>Tạo template đầu ra</h2>
          {canCreate ? (
            <form onSubmit={submit}>
              <label htmlFor="template-name">Tên template</label>
              <input
                id="template-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                minLength={2}
                maxLength={120}
                required
              />
              <div className="template-grid">
                <label>
                  Ngôn ngữ
                  <select
                    value={profile.language}
                    onChange={(event) =>
                      setProfile({
                        ...profile,
                        language: event.target.value as typeof profile.language,
                      })
                    }
                  >
                    <option value="vi">Tiếng Việt</option>
                    <option value="en">English</option>
                    <option value="auto">Theo video</option>
                  </select>
                </label>
                <label>
                  Đối tượng
                  <select
                    value={profile.audience}
                    onChange={(event) =>
                      setProfile({
                        ...profile,
                        audience: event.target.value as typeof profile.audience,
                      })
                    }
                  >
                    <option value="employee">Nhân viên</option>
                    <option value="student">Sinh viên</option>
                    <option value="professional">Chuyên gia</option>
                    <option value="general">Phổ thông</option>
                  </select>
                </label>
                <label>
                  Độ khó
                  <select
                    value={profile.difficulty}
                    onChange={(event) =>
                      setProfile({
                        ...profile,
                        difficulty: event.target
                          .value as typeof profile.difficulty,
                      })
                    }
                  >
                    <option value="beginner">Cơ bản</option>
                    <option value="intermediate">Trung bình</option>
                    <option value="advanced">Nâng cao</option>
                  </select>
                </label>
                <label>
                  Giọng điệu
                  <select
                    value={profile.tone}
                    onChange={(event) =>
                      setProfile({
                        ...profile,
                        tone: event.target.value as typeof profile.tone,
                      })
                    }
                  >
                    <option value="concise">Súc tích</option>
                    <option value="supportive">Khuyến khích</option>
                    <option value="formal">Trang trọng</option>
                  </select>
                </label>
                <label>
                  Flashcard
                  <input
                    type="number"
                    min={10}
                    max={20}
                    value={profile.flashcards}
                    onChange={(event) =>
                      setProfile({
                        ...profile,
                        flashcards: Number(event.target.value),
                      })
                    }
                  />
                </label>
                <label>
                  Câu quiz
                  <input
                    type="number"
                    min={5}
                    max={10}
                    value={profile.quizQuestions}
                    onChange={(event) =>
                      setProfile({
                        ...profile,
                        quizQuestions: Number(event.target.value),
                      })
                    }
                  />
                </label>
              </div>
              <button disabled={create.isPending}>
                {create.isPending ? 'Đang lưu…' : 'Lưu template'}
              </button>
              {create.isError && (
                <p className="form-error">
                  Template trùng tên hoặc có cấu hình không hợp lệ.
                </p>
              )}
            </form>
          ) : (
            <p>Vai trò hiện tại chỉ được xem template đã chuẩn hóa.</p>
          )}
          <div className="template-list">
            {templates.data
              ?.filter((item) => item.state === 'ACTIVE')
              .map((item) => (
                <article key={item.id}>
                  <strong>{item.name}</strong>
                  <span>
                    {item.outputProfile.language} ·{' '}
                    {item.outputProfile.difficulty} ·{' '}
                    {item.outputProfile.flashcards} thẻ ·{' '}
                    {item.outputProfile.quizQuestions} quiz
                  </span>
                </article>
              ))}
          </div>
        </section>
        <section className="panel">
          <p className="eyebrow">REVIEW QUEUE</p>
          <h2>{reviewQueue.data?.length ?? 0} bản đang chờ</h2>
          <label className="check-row approval-setting">
            <input
              type="checkbox"
              checked={settings.data?.approvalRequired ?? true}
              disabled={
                !settings.data || !canManageSettings || updateSettings.isPending
              }
              onChange={(event) => updateSettings.mutate(event.target.checked)}
            />
            Bắt buộc reviewer duyệt trước khi publish
          </label>
          {!canReview && (
            <p>Reviewer hoặc quản trị viên xử lý hàng đợi duyệt.</p>
          )}
          {reviewQueue.data?.map((item) => (
            <article className="catalog-row" key={item.packageId}>
              <Link to={`/app/packages/${item.packageId}`}>
                <strong>{item.title}</strong>
              </Link>
              <span>
                Bản {item.revisionNo} · {item.openFeedback} phản hồi mở
              </span>
            </article>
          ))}
          <p className="eyebrow question-bank-count">QUESTION BANK</p>
          <h2>{questionBank.data?.length ?? 0} câu đã human-verified</h2>
          <p>Chỉ câu hỏi từ revision được duyệt mới vào ngân hàng dùng lại.</p>
        </section>
      </div>
    </main>
  )
}
