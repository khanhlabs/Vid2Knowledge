import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { idempotencyKey } from '../../shared/api/client'
import {
  learnerApi,
  type Assignment,
  type AttemptResult,
  type DueCard,
  type ReviewRating,
} from './api'

export function LearnerPage() {
  const { organizationId = '' } = useParams()
  const [lesson, setLesson] = useState<Assignment | null>(null)
  const [answers, setAnswers] = useState<number[]>([])
  const [result, setResult] = useState<AttemptResult | null>(null)
  const [revealedCardId, setRevealedCardId] = useState<string | null>(null)
  const [pendingReview, setPendingReview] = useState<{
    card: DueCard
    rating: ReviewRating
    key: string
  } | null>(null)
  const queryClient = useQueryClient()
  const assignments = useQuery({
    queryKey: ['learner-assignments', organizationId],
    queryFn: () => learnerApi.assignments(organizationId),
    enabled: Boolean(organizationId),
  })
  const dueReviews = useQuery({
    queryKey: ['learner-reviews-due', organizationId],
    queryFn: () => learnerApi.dueReviews(organizationId),
    enabled: Boolean(organizationId) && !lesson,
  })
  const reviewSummary = useQuery({
    queryKey: ['learner-reviews-summary', organizationId],
    queryFn: () => learnerApi.reviewSummary(organizationId),
    enabled: Boolean(organizationId) && !lesson,
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
  const review = useMutation({
    mutationFn: (request: {
      card: DueCard
      rating: ReviewRating
      key: string
    }) =>
      learnerApi.reviewCard(
        organizationId,
        request.card.assignmentId,
        request.card.cardId,
        request.rating,
        request.key,
      ),
    retry: 2,
    onSuccess: async () => {
      setPendingReview(null)
      setRevealedCardId(null)
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['learner-reviews-due', organizationId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['learner-reviews-summary', organizationId],
        }),
      ])
    },
  })

  const rateCard = (card: DueCard, rating: ReviewRating) => {
    const request =
      pendingReview?.card.cardId === card.cardId &&
      pendingReview.rating === rating
        ? pendingReview
        : { card, rating, key: idempotencyKey('flashcard-review') }
    setPendingReview(request)
    review.mutate(request)
  }
  const currentReviewCard = dueReviews.data?.[0]

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
                    <li key={item.id}>{item.text}</li>
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
        <section className="learner-home">
          <section className="review-dashboard" aria-labelledby="review-title">
            <div className="review-heading">
              <div>
                <p className="eyebrow">ÔN TẬP THÍCH ỨNG · FSRS</p>
                <h1 id="review-title">Nhớ lâu hơn, đúng lúc hơn.</h1>
              </div>
              <div className="review-stats" aria-label="Thống kê ôn tập">
                <span>
                  <strong>{reviewSummary.data?.dueCards ?? 0}</strong> cần ôn
                </span>
                <span>
                  <strong>{reviewSummary.data?.currentStreakDays ?? 0}</strong>{' '}
                  ngày liên tiếp
                </span>
                <span>
                  <strong>{reviewSummary.data?.masteredCards ?? 0}</strong> đã
                  vững
                </span>
              </div>
            </div>
            {dueReviews.isPending && <p>Đang chuẩn bị thẻ cần ôn…</p>}
            {dueReviews.data?.length === 0 && (
              <div className="empty-state">
                Bạn đã hoàn tất lượt ôn hiện tại. Hệ thống sẽ đưa thẻ trở lại
                đúng lúc trí nhớ bắt đầu giảm.
              </div>
            )}
            {currentReviewCard && (
              <article className="review-card">
                <span className="status-pill">
                  {currentReviewCard.state === 'NEW' ? 'THẺ MỚI' : 'CẦN ÔN'}
                </span>
                <small>{currentReviewCard.assignmentTitle}</small>
                <h2>{currentReviewCard.question}</h2>
                {revealedCardId !== currentReviewCard.cardId ? (
                  <button
                    onClick={() => setRevealedCardId(currentReviewCard.cardId)}
                  >
                    Hiện đáp án
                  </button>
                ) : (
                  <div className="review-answer">
                    <p>{currentReviewCard.answer}</p>
                    {currentReviewCard.youtubeUrl && (
                      <a
                        href={`${currentReviewCard.youtubeUrl}&t=${currentReviewCard.timestampSeconds}s`}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Kiểm chứng tại {currentReviewCard.timestampSeconds}s ↗
                      </a>
                    )}
                    <div className="rating-grid">
                      {(
                        [
                          ['AGAIN', 'Quên'],
                          ['HARD', 'Khó'],
                          ['GOOD', 'Nhớ'],
                          ['EASY', 'Rất dễ'],
                        ] as const
                      ).map(([rating, label]) => (
                        <button
                          key={rating}
                          disabled={review.isPending}
                          onClick={() => rateCard(currentReviewCard, rating)}
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                    {review.isError && (
                      <p className="form-error">
                        Chưa lưu được đánh giá. Bấm lại cùng mức để retry an
                        toàn.
                      </p>
                    )}
                  </div>
                )}
              </article>
            )}
          </section>
          <section className="assignment-list">
            <p className="eyebrow">BÀI ĐƯỢC GIAO</p>
            <h2>Tiếp tục tiến độ của bạn.</h2>
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
        </section>
      )}
    </main>
  )
}
