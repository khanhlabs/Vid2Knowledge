import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSessionMutation as useMutation } from '../../shared/hooks/useSessionMutation'
import { useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { workspaceApi } from './api'

export function CatalogPage() {
  const organizationId = localStorage.getItem('v2k.organizationId') ?? ''
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const [packageId, setPackageId] = useState('')
  const [learnerIds, setLearnerIds] = useState<string[]>([])
  const packages = useQuery({
    queryKey: ['packages', organizationId],
    queryFn: () => workspaceApi.packages(organizationId),
    enabled: Boolean(organizationId),
  })
  const members = useQuery({
    queryKey: ['members', organizationId],
    queryFn: () => workspaceApi.members(organizationId),
    enabled: Boolean(organizationId),
  })
  const courses = useQuery({
    queryKey: ['courses', organizationId],
    queryFn: () => workspaceApi.courses(organizationId),
    enabled: Boolean(organizationId),
  })
  const cohorts = useQuery({
    queryKey: ['cohorts', organizationId],
    queryFn: () => workspaceApi.cohorts(organizationId),
    enabled: Boolean(organizationId),
  })
  const learners = useMemo(
    () =>
      members.data?.filter(
        (member) => member.role === 'LEARNER' && member.status === 'ACTIVE',
      ) ?? [],
    [members.data],
  )
  const launch = useMutation({
    mutationFn: () =>
      workspaceApi.launchProgram(organizationId, {
        title,
        packageId,
        learnerIds,
        availableAt: new Date().toISOString(),
        dueAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      }),
    onSuccess: async () => {
      setTitle('')
      setPackageId('')
      setLearnerIds([])
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['courses', organizationId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['cohorts', organizationId],
        }),
      ])
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    launch.mutate()
  }
  if (!organizationId)
    return (
      <div className="screen-message">
        Hãy chọn tổ chức trong workspace trước.
      </div>
    )

  return (
    <main className="workspace-page catalog-page">
      <header className="app-header">
        <Link to="/app">← Workspace</Link>
        <Link to="/app/analytics">Báo cáo kết quả →</Link>
      </header>
      <section className="workspace-heading">
        <div>
          <p className="eyebrow">PILOT → RECURRING</p>
          <h1>Khởi chạy cohort trong một giao dịch.</h1>
          <p>
            Chọn học liệu đã được duyệt và học viên; hệ thống tạo course,
            cohort, assignment rồi publish đồng bộ.
          </p>
        </div>
      </section>
      <div className="workspace-grid">
        <section className="panel">
          <h2>Chương trình mới</h2>
          <form onSubmit={submit}>
            <label htmlFor="program-title">Tên chương trình</label>
            <input
              id="program-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              required
            />
            <label htmlFor="program-package">Học liệu đã xuất bản</label>
            <select
              id="program-package"
              value={packageId}
              onChange={(event) => setPackageId(event.target.value)}
              required
            >
              <option value="">Chọn học liệu</option>
              {packages.data
                ?.filter((item) => item.state === 'PUBLISHED')
                .map((item) => (
                  <option value={item.id} key={item.id}>
                    {item.title}
                  </option>
                ))}
            </select>
            <fieldset>
              <legend>Học viên nhận bài</legend>
              {learners.map((learner) => (
                <label key={learner.id} className="check-row">
                  <input
                    type="checkbox"
                    checked={learnerIds.includes(learner.id)}
                    onChange={(event) =>
                      setLearnerIds((current) =>
                        event.target.checked
                          ? [...current, learner.id]
                          : current.filter((id) => id !== learner.id),
                      )
                    }
                  />{' '}
                  {learner.displayName} · {learner.email}
                </label>
              ))}
              {learners.length === 0 && (
                <p>Hãy mời ít nhất một học viên trong workspace.</p>
              )}
            </fieldset>
            <button disabled={launch.isPending || learnerIds.length === 0}>
              {launch.isPending ? 'Đang khởi chạy…' : 'Khởi chạy và giao bài'}
            </button>
            {launch.isSuccess && (
              <p className="notice">
                Chương trình đã được publish và giao thành công.
              </p>
            )}
            {launch.isError && (
              <p className="form-error">
                Không thể khởi chạy. Học liệu phải ở trạng thái PUBLISHED.
              </p>
            )}
          </form>
        </section>
        <section className="panel">
          <p className="eyebrow">DANH MỤC</p>
          <h2>{courses.data?.length ?? 0} chương trình</h2>
          {courses.data?.map((course) => (
            <article className="catalog-row" key={course.id}>
              <strong>{course.title}</strong>
              <span>
                {course.lessonCount} bài · {course.state}
              </span>
            </article>
          ))}
          <h2>{cohorts.data?.length ?? 0} cohort</h2>
          {cohorts.data?.map((cohort) => (
            <article className="catalog-row" key={cohort.id}>
              <strong>{cohort.name}</strong>
              <span>
                {cohort.memberCount} học viên · {cohort.status}
              </span>
            </article>
          ))}
        </section>
      </div>
    </main>
  )
}
