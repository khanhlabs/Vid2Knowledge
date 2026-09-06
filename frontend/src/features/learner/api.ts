import { api, idempotencyKey } from '../../shared/api/client'

export interface AssignmentSummary {
  id: string
  title: string
  availableAt: string
  dueAt?: string
  status: string
  progressPercent: number
  bestScorePercent?: number
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
}
