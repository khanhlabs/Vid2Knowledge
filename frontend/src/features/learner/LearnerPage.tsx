import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { idempotencyKey } from '../../shared/api/client'
import {
  learnerApi,
  type Assignment,
  type AssessmentMode,
  type AssessmentResultV2,
  type AssessmentSnapshot,
  type Certificate,
  type LearningPath,
  type DueCard,
  type GroundedAnswer,
  type ReviewRating,
} from './api'

export function LearnerPage() {
  const { organizationId = '' } = useParams()
  const [lesson, setLesson] = useState<Assignment | null>(null)
  const [answers, setAnswers] = useState<number[]>([])
  const [assessment, setAssessment] = useState<AssessmentSnapshot | null>(null)
  const [result, setResult] = useState<AssessmentResultV2 | null>(null)
  const [assessmentStart, setAssessmentStart] = useState<{
    mode: AssessmentMode
    key: string
  } | null>(null)
  const [submissionKey, setSubmissionKey] = useState<string | null>(null)
  const [certificate, setCertificate] = useState<Certificate | null>(null)
  const [qaQuestion, setQaQuestion] = useState('')
  const [qaAnswer, setQaAnswer] = useState<GroundedAnswer | null>(null)
  const [qaRequest, setQaRequest] = useState<{
    question: string
    key: string
  } | null>(null)
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
  const learningPaths = useQuery({
    queryKey: ['learner-paths', organizationId],
    queryFn: () => learnerApi.learningPaths(organizationId),
    enabled: Boolean(organizationId) && !lesson,
  })
  const assessmentOverview = useQuery({
    queryKey: ['assessment-overview', organizationId, lesson?.id],
    queryFn: () => learnerApi.assessmentOverview(organizationId, lesson!.id),
    enabled: Boolean(organizationId && lesson?.id),
  })
  const start = useMutation({
    mutationFn: (id: string) => learnerApi.start(organizationId, id),
    onSuccess: (item) => {
      setLesson(item)
      setAssessment(null)
      setAnswers([])
      setResult(null)
      setAssessmentStart(null)
      setSubmissionKey(null)
      setQaQuestion('')
      setQaAnswer(null)
      setQaRequest(null)
    },
  })
  const startAssessment = useMutation({
    mutationFn: (request: { mode: AssessmentMode; key: string }) =>
      learnerApi.startAssessment(
        organizationId,
        lesson!.id,
        request.mode,
        request.key,
      ),
    retry: 2,
    onSuccess: (snapshot) => {
      setAssessment(snapshot)
      setAnswers(Array(snapshot.questions.length).fill(-1))
      setResult(null)
      setSubmissionKey(null)
    },
  })
  const submitAssessment = useMutation({
    mutationFn: (request: {
      snapshot: AssessmentSnapshot
      answers: number[]
      key: string
    }) =>
      learnerApi.submitAssessment(
        organizationId,
        request.snapshot.snapshotId,
        request.answers,
        request.key,
      ),
    retry: 2,
    onSuccess: async (submitted) => {
      setResult(submitted)
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['assessment-overview', organizationId, lesson?.id],
        }),
        queryClient.invalidateQueries({
          queryKey: ['learner-assignments', organizationId],
        }),
      ])
    },
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
  const issueCertificate = useMutation({
    mutationFn: (path: LearningPath) =>
      learnerApi.issueCertificate(organizationId, path.courseId, path.cohortId),
    onSuccess: setCertificate,
  })
  const ask = useMutation({
    mutationFn: (request: { question: string; key: string }) =>
      learnerApi.ask(organizationId, lesson!.id, request.question, request.key),
    retry: 2,
    onSuccess: setQaAnswer,
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

  const beginAssessment = (mode: AssessmentMode) => {
    const request =
      assessmentStart?.mode === mode
        ? assessmentStart
        : { mode, key: idempotencyKey('assessment-start') }
    setAssessmentStart(request)
    startAssessment.mutate(request)
  }

  const submitCurrentAssessment = () => {
    if (!assessment) return
    const key = submissionKey ?? idempotencyKey('assessment-submit')
    setSubmissionKey(key)
    submitAssessment.mutate({ snapshot: assessment, answers, key })
  }

  const askGroundedQuestion = () => {
    const question = qaQuestion.trim()
    if (!question) return
    const request =
      qaRequest?.question === question
        ? qaRequest
        : { question, key: idempotencyKey('qa-question') }
    setQaRequest(request)
    ask.mutate(request)
  }

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
            onClick={() => {
              setLesson(null)
              setAssessment(null)
              setResult(null)
            }}
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
            <section className="grounded-qa" aria-labelledby="qa-title">
              <p className="eyebrow">ASK VIDEO · CÓ NGUỒN</p>
              <h2 id="qa-title">Hỏi trong phạm vi bài đã duyệt</h2>
              <textarea
                value={qaQuestion}
                maxLength={1000}
                rows={3}
                placeholder="Ví dụ: Vì sao bước này quan trọng?"
                onChange={(event) => {
                  setQaQuestion(event.target.value)
                  setQaRequest(null)
                }}
              />
              <button
                disabled={ask.isPending || !qaQuestion.trim()}
                onClick={askGroundedQuestion}
              >
                {ask.isPending ? 'Đang đối chiếu nguồn…' : 'Hỏi bài học'}
              </button>
              {qaAnswer && (
                <div className="qa-answer" role="status">
                  <p>{qaAnswer.answer}</p>
                  {qaAnswer.citations.map((citation) => (
                    <a
                      key={`${citation.itemType}-${citation.itemId}`}
                      href={
                        lesson.content.video?.youtubeUrl
                          ? `${lesson.content.video.youtubeUrl}&t=${citation.timestampSeconds}s`
                          : undefined
                      }
                      target="_blank"
                      rel="noreferrer"
                    >
                      [{citation.position}] {citation.evidence} ·{' '}
                      {citation.timestampSeconds}s ↗
                    </a>
                  ))}
                </div>
              )}
              {ask.isError && (
                <p className="form-error">
                  Chưa thể trả lời. Nội dung có thể chưa được lập chỉ mục hoặc
                  quota hỏi đáp đã hết.
                </p>
              )}
            </section>
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
            <p className="eyebrow">EXAM MODE · SNAPSHOT BẤT BIẾN</p>
            {!assessment && (
              <div className="assessment-launcher">
                <h2>Đo mức hiểu, không lộ đáp án.</h2>
                <p>
                  Mỗi lượt dùng thứ tự câu và đáp án riêng. Kết quả luôn giữ
                  nguyên dù nội dung bài học được cập nhật sau này.
                </p>
                <div className="assessment-stats">
                  <span>
                    <strong>
                      {assessmentOverview.data?.practiceAttempts ?? 0}
                    </strong>{' '}
                    lượt luyện
                  </span>
                  <span>
                    <strong>
                      {assessmentOverview.data?.bestScorePercent ?? '—'}
                    </strong>
                    {assessmentOverview.data?.bestScorePercent != null
                      ? '% tốt nhất'
                      : ' chưa có điểm'}
                  </span>
                </div>
                <button
                  disabled={startAssessment.isPending}
                  onClick={() => beginAssessment('PRACTICE')}
                >
                  Bắt đầu đề luyện mới
                </button>
                <button
                  className="secondary-button"
                  disabled={
                    startAssessment.isPending ||
                    !assessmentOverview.data?.delayedRecallAvailable
                  }
                  onClick={() => beginAssessment('DELAYED_RECALL')}
                >
                  Kiểm tra nhớ lại sau 3 ngày
                </button>
                {assessmentOverview.data?.delayedRecallAvailableAt &&
                  !assessmentOverview.data.delayedRecallAvailable && (
                    <small>
                      Mở delayed recall lúc{' '}
                      {new Date(
                        assessmentOverview.data.delayedRecallAvailableAt,
                      ).toLocaleString('vi-VN')}
                    </small>
                  )}
                {assessmentOverview.data?.weakAreas.length ? (
                  <div className="weak-areas">
                    <h3>Điểm yếu cần xử lý</h3>
                    <ul>
                      {assessmentOverview.data.weakAreas.map((area) => (
                        <li key={area.questionId}>
                          {area.question} · sai {area.wrongCount} lần
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                {startAssessment.isError && (
                  <p className="form-error">
                    Chưa thể tạo đề. Nếu là delayed recall, hãy kiểm tra thời
                    điểm mở đề.
                  </p>
                )}
              </div>
            )}
            {assessment?.questions.map((question, questionIndex) => (
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
            {assessment && !result && (
              <button
                disabled={
                  submitAssessment.isPending ||
                  answers.some((answer) => answer < 0)
                }
                onClick={submitCurrentAssessment}
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
                <div className="answer-review">
                  {result.questions
                    .filter((question) => !question.correct)
                    .map((question) => (
                      <article key={question.questionId}>
                        <strong>{question.question}</strong>
                        <p>{question.explanation}</p>
                        {question.youtubeUrl && (
                          <a
                            href={`${question.youtubeUrl}&t=${question.timestampSeconds}s`}
                            target="_blank"
                            rel="noreferrer"
                          >
                            Xem bằng chứng tại {question.timestampSeconds}s ↗
                          </a>
                        )}
                      </article>
                    ))}
                </div>
                <button
                  className="secondary-button"
                  onClick={() => {
                    setAssessment(null)
                    setResult(null)
                    setAnswers([])
                    setAssessmentStart(null)
                  }}
                >
                  Tạo đề khác
                </button>
              </div>
            )}
            {submitAssessment.isError && (
              <p className="form-error">
                Không thể nộp bài. Bấm lại để retry an toàn với cùng mã yêu cầu.
              </p>
            )}
          </aside>
        </section>
      ) : (
        <section className="learner-home">
          {learningPaths.data?.length ? (
            <section className="learning-paths" aria-labelledby="paths-title">
              <p className="eyebrow">LỘ TRÌNH ĐÀO TẠO</p>
              <h1 id="paths-title">
                Tiến độ có điều kiện, kết quả có thể xác minh.
              </h1>
              <div className="path-grid">
                {learningPaths.data.map((path) => (
                  <article key={`${path.courseId}-${path.cohortId}`}>
                    <span className="status-pill">{path.cohortName}</span>
                    <h2>{path.title}</h2>
                    <p>
                      {path.completedLessons}/{path.totalLessons} bài đạt từ{' '}
                      {path.passingScorePercent}%
                      {path.requireDelayedRecall
                        ? ' · yêu cầu delayed recall'
                        : ''}
                    </p>
                    <ol>
                      {path.lessons.map((pathLesson) => (
                        <li key={pathLesson.lessonId}>
                          <span>{pathLesson.unlocked ? 'Mở' : 'Khóa'}</span>{' '}
                          {pathLesson.title}
                          {pathLesson.bestScorePercent != null
                            ? ` · ${pathLesson.bestScorePercent}%`
                            : ''}
                        </li>
                      ))}
                    </ol>
                    <button
                      disabled={
                        !path.certificateEligible || issueCertificate.isPending
                      }
                      onClick={() => issueCertificate.mutate(path)}
                    >
                      Nhận certificate nội bộ
                    </button>
                  </article>
                ))}
              </div>
              {certificate && (
                <div className="certificate-result" role="status">
                  <strong>{certificate.courseTitle}</strong>
                  <span>Mã xác thực: {certificate.verificationCode}</span>
                  <small>
                    Certificate hoàn thành nội bộ, không phải văn bằng/chứng chỉ
                    được cơ quan nhà nước công nhận.
                  </small>
                </div>
              )}
              {issueCertificate.isError && (
                <p className="form-error">
                  Chưa đủ điều kiện cấp certificate hoặc không thể lưu lúc này.
                </p>
              )}
            </section>
          ) : null}
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
                  disabled={start.isPending || !item.unlocked}
                  onClick={() => start.mutate(item.id)}
                >
                  {item.unlocked ? 'Bắt đầu' : 'Hoàn tất bài trước để mở'}
                </button>
              </article>
            ))}
          </section>
        </section>
      )}
    </main>
  )
}
