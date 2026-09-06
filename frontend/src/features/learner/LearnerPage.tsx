import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { learnerApi, type Assignment, type AttemptResult } from './api'

export function LearnerPage() {
  const { organizationId = '' } = useParams()
  const [lesson, setLesson] = useState<Assignment | null>(null)
  const [answers, setAnswers] = useState<number[]>([])
  const [result, setResult] = useState<AttemptResult | null>(null)
  const assignments = useQuery({
    queryKey: ['learner-assignments', organizationId],
    queryFn: () => learnerApi.assignments(organizationId),
    enabled: Boolean(organizationId),
  })
  const start = useMutation({
    mutationFn: (id: string) => learnerApi.start(organizationId, id),
    onSuccess: (item) => {
      setLesson(item)
      setAnswers(Array(item.content.quiz?.length ?? 0).fill(-1))
      setResult(null)
    },
  })
  const submit = useMutation({
    mutationFn: () => learnerApi.submit(organizationId, lesson!.id, answers),
    onSuccess: setResult,
  })

  return (
    <main className="learner-page">
      <header className="learner-header">
        <Link to="/app">Vid2Knowledge</Link>
        <span>Không gian học tập</span>
      </header>
      {lesson ? (
        <section className="lesson-layout">
          <button
            className="text-button back-button"
            onClick={() => setLesson(null)}
          >
            ← Danh sách bài học
          </button>
          <article className="lesson-content">
            <p className="eyebrow">BÀI HỌC</p>
            <h1>{lesson.title}</h1>
            {lesson.content.video?.youtubeUrl && (
              <a
                href={lesson.content.video.youtubeUrl}
                target="_blank"
                rel="noreferrer"
              >
                Mở video nguồn ↗
              </a>
            )}
            <p className="lesson-overview">
              {lesson.content.summary?.overview}
            </p>
            {lesson.content.keyTakeaways && (
              <>
                <h2>Điểm cần nhớ</h2>
                <ul>
                  {lesson.content.keyTakeaways.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </>
            )}
          </article>
          <aside className="quiz-card">
            <p className="eyebrow">KIỂM TRA KIẾN THỨC</p>
            {lesson.content.quiz?.map((question, questionIndex) => (
              <fieldset key={`${question.question}-${questionIndex}`}>
                <legend>
                  {questionIndex + 1}. {question.question}
                </legend>
                {question.options.map((option, optionIndex) => (
                  <label key={option}>
                    <input
                      type="radio"
                      name={`q-${questionIndex}`}
                      checked={answers[questionIndex] === optionIndex}
                      onChange={() =>
                        setAnswers((current) =>
                          current.map((answer, index) =>
                            index === questionIndex ? optionIndex : answer,
                          ),
                        )
                      }
                    />{' '}
                    {option}
                  </label>
                ))}
              </fieldset>
            ))}
            {!result && (
              <button
                disabled={
                  submit.isPending || answers.some((answer) => answer < 0)
                }
                onClick={() => submit.mutate()}
              >
                Nộp bài
              </button>
            )}
            {result && (
              <div className="score-result">
                <strong>{result.scorePercent}%</strong>
                <span>
                  {result.correctCount}/{result.questionCount} câu đúng
                </span>
              </div>
            )}
            {submit.isError && (
              <p className="form-error">
                Không thể nộp bài. Hãy kiểm tra hạn nộp và thử lại.
              </p>
            )}
          </aside>
        </section>
      ) : (
        <section className="assignment-list">
          <p className="eyebrow">BÀI ĐƯỢC GIAO</p>
          <h1>Tiếp tục tiến độ của bạn.</h1>
          {assignments.isPending && <p>Đang tải bài học…</p>}
          {assignments.data?.length === 0 && (
            <div className="empty-state">Chưa có bài học nào được giao.</div>
          )}
          {assignments.data?.map((item) => (
            <article key={item.id}>
              <div>
                <span className="status-pill">{item.status}</span>
                <h2>{item.title}</h2>
                <p>
                  Tiến độ {item.progressPercent}%
                  {item.bestScorePercent != null
                    ? ` · Điểm cao nhất ${item.bestScorePercent}%`
                    : ''}
                </p>
              </div>
              <button
                disabled={start.isPending}
                onClick={() => start.mutate(item.id)}
              >
                Bắt đầu
              </button>
            </article>
          ))}
        </section>
      )}
    </main>
  )
}
