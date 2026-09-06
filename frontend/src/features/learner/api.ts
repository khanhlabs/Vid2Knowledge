import { api, idempotencyKey } from '../../shared/api/client'

export interface AssignmentSummary {
  id: string
  title: string
  availableAt: string
  dueAt?: string
  status: string
  progressPercent: number
  bestScorePercent?: number
  unlocked: boolean
}
export interface QuizQuestion {
  question: string
  options: string[]
}
export interface Assignment extends AssignmentSummary {
  packageRevisionId: string
  content: {
    video?: { title?: string; youtubeUrl?: string }
    summary?: {
      overview?: string
      sections?: Array<{ title: string; content: string[] }>
    }
    keyTakeaways?: Array<{
      id: string
      text: string
      source: { timestampSeconds: number; evidence: string }
    }>
    flashcards?: Array<{ question: string; answer: string }>
    quiz?: QuizQuestion[]
  }
}
export interface AttemptResult {
  attemptId: string
  scorePercent: number
  correctCount: number
  questionCount: number
  submittedAt: string
}

export type AssessmentMode = 'PRACTICE' | 'DELAYED_RECALL'

export interface AssessmentQuestion {
  id: string
  question: string
  options: string[]
}

export interface AssessmentSnapshot {
  snapshotId: string
  assignmentId: string
  mode: AssessmentMode
  questions: AssessmentQuestion[]
  startedAt: string
  expiresAt: string
}

export interface AssessmentQuestionResult {
  questionId: string
  question: string
  selectedAnswerIndex: number
  correctAnswerIndex: number
  correct: boolean
  explanation: string
  youtubeUrl?: string
  timestampSeconds: number
  evidence: string
}

export interface AssessmentResultV2 {
  attemptId: string
  snapshotId: string
  mode: AssessmentMode
  scorePercent: number
  correctCount: number
  questionCount: number
  submittedAt: string
  questions: AssessmentQuestionResult[]
}

export interface AssessmentOverview {
  practiceAttempts: number
  delayedRecallAttempts: number
  bestScorePercent?: number
  delayedRecallAvailableAt?: string
  delayedRecallAvailable: boolean
  delayedRecallCompleted: boolean
  weakAreas: Array<{
    questionId: string
    question: string
    wrongCount: number
    affectedAttempts: number
  }>
}

export type ReviewRating = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY'

export interface DueCard {
  assignmentId: string
  assignmentTitle: string
  packageRevisionId: string
  cardId: string
  question: string
  answer: string
  youtubeUrl?: string
  timestampSeconds: number
  verificationStatus: string
  state: string
  dueAt: string
  stabilityDays: number
  difficulty: number
  reviewCount: number
  lapseCount: number
}

export interface ReviewSummary {
  totalCards: number
  dueCards: number
  masteredCards: number
  reviewsToday: number
  currentStreakDays: number
}

export interface ReviewResult {
  reviewId: string
  cardId: string
  rating: ReviewRating
  nextDueAt: string
  stabilityDays: number
  difficulty: number
  state: string
  reviewCount: number
  lapseCount: number
  reviewedAt: string
}

export interface LearningPath {
  courseId: string
  title: string
  description: string
  cohortId: string
  cohortName: string
  passingScorePercent: number
  requireDelayedRecall: boolean
  completedLessons: number
  totalLessons: number
  certificateEligible: boolean
  lessons: Array<{
    lessonId: string
    assignmentId: string
    title: string
    status: string
    bestScorePercent?: number
    delayedRecallCompleted: boolean
    unlocked: boolean
  }>
}

export interface Certificate {
  id: string
  courseId: string
  cohortId: string
  verificationCode: string
  learnerName: string
  courseTitle: string
  cohortName: string
  organizationName: string
  issuedAt: string
  revoked: boolean
}

export const learnerApi = {
  assignments: (organizationId: string) =>
    api<AssignmentSummary[]>(
      `/api/v1/organizations/${organizationId}/learner/assignments`,
    ),
  start: (organizationId: string, assignmentId: string) =>
    api<Assignment>(
      `/api/v1/organizations/${organizationId}/learner/assignments/${assignmentId}/start`,
      { method: 'POST' },
    ),
  submit: (organizationId: string, assignmentId: string, answers: number[]) =>
    api<AttemptResult>(
      `/api/v1/organizations/${organizationId}/learner/assignments/${assignmentId}/attempts`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey('attempt') },
        body: JSON.stringify({ answers }),
      },
    ),
  dueReviews: (organizationId: string) =>
    api<DueCard[]>(
      `/api/v1/organizations/${organizationId}/learner/reviews/due`,
    ),
  reviewSummary: (organizationId: string) =>
    api<ReviewSummary>(
      `/api/v1/organizations/${organizationId}/learner/reviews/summary`,
    ),
  learningPaths: (organizationId: string) =>
    api<LearningPath[]>(
      `/api/v1/organizations/${organizationId}/learner/paths`,
    ),
  issueCertificate: (
    organizationId: string,
    courseId: string,
    cohortId: string,
  ) =>
    api<Certificate>(
      `/api/v1/organizations/${organizationId}/learner/paths/${courseId}/cohorts/${cohortId}/certificate`,
      { method: 'POST' },
    ),
  reviewCard: (
    organizationId: string,
    assignmentId: string,
    cardId: string,
    rating: ReviewRating,
    requestKey: string,
  ) =>
    api<ReviewResult>(
      `/api/v1/organizations/${organizationId}/learner/assignments/${assignmentId}/flashcards/${encodeURIComponent(cardId)}/reviews`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': requestKey },
        body: JSON.stringify({ rating }),
      },
    ),
  assessmentOverview: (organizationId: string, assignmentId: string) =>
    api<AssessmentOverview>(
      `/api/v1/organizations/${organizationId}/learner/assignments/${assignmentId}/assessments/overview`,
    ),
  startAssessment: (
    organizationId: string,
    assignmentId: string,
    mode: AssessmentMode,
    requestKey: string,
  ) =>
    api<AssessmentSnapshot>(
      `/api/v1/organizations/${organizationId}/learner/assignments/${assignmentId}/assessments`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': requestKey },
        body: JSON.stringify({ mode }),
      },
    ),
  submitAssessment: (
    organizationId: string,
    snapshotId: string,
    answers: number[],
    requestKey: string,
  ) =>
    api<AssessmentResultV2>(
      `/api/v1/organizations/${organizationId}/learner/assessments/${snapshotId}/submit`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': requestKey },
        body: JSON.stringify({ answers }),
      },
    ),
}
