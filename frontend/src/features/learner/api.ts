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
}
