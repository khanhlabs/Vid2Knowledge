import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LearnerPage } from './LearnerPage'

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={['/learn/organization-1']}>
      <QueryClientProvider client={client}>
        <Routes>
          <Route path="/learn/:organizationId" element={<LearnerPage />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('LearnerPage adaptive review', () => {
  it('reveals a due card and submits the learner rating idempotently', async () => {
    const requests: Array<{ url: string; init?: RequestInit }> = []
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      requests.push({ url, init })
      if (url.endsWith('/learner/assignments')) return Promise.resolve(json([]))
      if (url.endsWith('/learner/paths')) return Promise.resolve(json([]))
      if (url.endsWith('/learner/reviews/summary')) {
        return Promise.resolve(
          json({
            totalCards: 2,
            dueCards: 1,
            masteredCards: 0,
            reviewsToday: 0,
            currentStreakDays: 0,
          }),
        )
      }
      if (url.endsWith('/learner/reviews/due')) {
        return Promise.resolve(
          json([
            {
              assignmentId: 'assignment-1',
              assignmentTitle: 'Bài học bán hàng',
              packageRevisionId: 'revision-1',
              cardId: 'flash-1',
              question: 'Khái niệm quan trọng là gì?',
              answer: 'Đáp án có căn cứ.',
              youtubeUrl: 'https://www.youtube.com/watch?v=abcdefghijk',
              timestampSeconds: 42,
              verificationStatus: 'verified',
              state: 'NEW',
              dueAt: '1970-01-01T00:00:00Z',
              stabilityDays: 0,
              difficulty: 0,
              reviewCount: 0,
              lapseCount: 0,
            },
          ]),
        )
      }
      if (url.includes('/flashcards/flash-1/reviews')) {
        return Promise.resolve(
          json({
            reviewId: 'review-1',
            cardId: 'flash-1',
            rating: 'GOOD',
            nextDueAt: '2026-09-08T00:00:00Z',
            stabilityDays: 2.3065,
            difficulty: 2.1,
            state: 'REVIEW',
            reviewCount: 1,
            lapseCount: 0,
            reviewedAt: '2026-09-06T00:00:00Z',
          }),
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    renderPage()

    expect(
      await screen.findByRole('heading', {
        name: 'Khái niệm quan trọng là gì?',
      }),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Hiện đáp án' }))
    expect(screen.getByText('Đáp án có căn cứ.')).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /Kiểm chứng tại 42s/ }),
    ).toHaveAttribute(
      'href',
      'https://www.youtube.com/watch?v=abcdefghijk&t=42s',
    )
    await userEvent.click(screen.getByRole('button', { name: 'Nhớ' }))

    await waitFor(() => {
      const reviewRequest = requests.find((request) =>
        request.url.includes('/flashcards/flash-1/reviews'),
      )
      expect(reviewRequest?.init?.method).toBe('POST')
      expect(
        new Headers(reviewRequest?.init?.headers).get('Idempotency-Key'),
      ).toMatch(/^flashcard-review-/)
      expect(reviewRequest?.init?.body).toBe(JSON.stringify({ rating: 'GOOD' }))
    })
  })

  it('starts a randomized exam and reveals evidence only after submission', async () => {
    const requests: Array<{ url: string; init?: RequestInit }> = []
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      requests.push({ url, init })
      if (url.endsWith('/learner/assignments')) {
        return Promise.resolve(
          json([
            {
              id: 'assignment-1',
              title: 'Bài kiểm tra bán hàng',
              availableAt: '2026-09-06T00:00:00Z',
              status: 'ASSIGNED',
              progressPercent: 0,
              unlocked: true,
            },
          ]),
        )
      }
      if (url.endsWith('/learner/paths')) return Promise.resolve(json([]))
      if (url.endsWith('/learner/reviews/summary')) {
        return Promise.resolve(
          json({
            totalCards: 0,
            dueCards: 0,
            masteredCards: 0,
            reviewsToday: 0,
            currentStreakDays: 0,
          }),
        )
      }
      if (url.endsWith('/learner/reviews/due')) return Promise.resolve(json([]))
      if (url.endsWith('/assignments/assignment-1/start')) {
        return Promise.resolve(
          json({
            id: 'assignment-1',
            title: 'Bài kiểm tra bán hàng',
            packageRevisionId: 'revision-1',
            availableAt: '2026-09-06T00:00:00Z',
            status: 'STARTED',
            progressPercent: 1,
            content: { summary: { overview: 'Nội dung bài học.' } },
          }),
        )
      }
      if (url.endsWith('/assessments/overview')) {
        return Promise.resolve(
          json({
            practiceAttempts: 0,
            delayedRecallAttempts: 0,
            delayedRecallAvailable: false,
            delayedRecallCompleted: false,
            weakAreas: [],
          }),
        )
      }
      if (url.endsWith('/assignments/assignment-1/assessments')) {
        return Promise.resolve(
          json({
            snapshotId: 'snapshot-1',
            assignmentId: 'assignment-1',
            mode: 'PRACTICE',
            questions: [
              {
                id: 'quiz-1',
                question: 'Bước quan trọng nhất là gì?',
                options: ['A', 'B', 'C', 'D'],
              },
            ],
            startedAt: '2026-09-06T00:00:00Z',
            expiresAt: '2026-09-06T02:00:00Z',
          }),
        )
      }
      if (url.endsWith('/assessments/snapshot-1/submit')) {
        return Promise.resolve(
          json({
            attemptId: 'attempt-1',
            snapshotId: 'snapshot-1',
            mode: 'PRACTICE',
            scorePercent: 0,
            correctCount: 0,
            questionCount: 1,
            submittedAt: '2026-09-06T00:10:00Z',
            questions: [
              {
                questionId: 'quiz-1',
                question: 'Bước quan trọng nhất là gì?',
                selectedAnswerIndex: 0,
                correctAnswerIndex: 1,
                correct: false,
                explanation: 'Giải thích có căn cứ.',
                youtubeUrl: 'https://www.youtube.com/watch?v=abcdefghijk',
                timestampSeconds: 84,
                evidence: 'Bằng chứng nguồn.',
              },
            ],
          }),
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    renderPage()

    await userEvent.click(
      await screen.findByRole('button', { name: 'Bắt đầu' }),
    )
    await userEvent.click(
      await screen.findByRole('button', { name: 'Bắt đầu đề luyện mới' }),
    )
    expect(
      await screen.findByRole('group', {
        name: /Bước quan trọng nhất là gì\?/,
      }),
    ).toBeInTheDocument()
    expect(screen.queryByText('Giải thích có căn cứ.')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: 'A' }))
    await userEvent.click(screen.getByRole('button', { name: 'Nộp bài' }))

    expect(await screen.findByText('Giải thích có căn cứ.')).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /Xem bằng chứng tại 84s/ }),
    ).toHaveAttribute(
      'href',
      'https://www.youtube.com/watch?v=abcdefghijk&t=84s',
    )
    const startRequest = requests.find((request) =>
      request.url.endsWith('/assignments/assignment-1/assessments'),
    )
    expect(startRequest?.init?.body).toBe(JSON.stringify({ mode: 'PRACTICE' }))
    expect(
      new Headers(startRequest?.init?.headers).get('Idempotency-Key'),
    ).toMatch(/^assessment-start-/)
    const submitRequest = requests.find((request) =>
      request.url.endsWith('/assessments/snapshot-1/submit'),
    )
    expect(submitRequest?.init?.body).toBe(JSON.stringify({ answers: [0] }))
    expect(
      new Headers(submitRequest?.init?.headers).get('Idempotency-Key'),
    ).toMatch(/^assessment-submit-/)
  })

  it('issues an internal certificate only for an eligible learning path', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      if (url.endsWith('/learner/assignments')) return Promise.resolve(json([]))
      if (url.endsWith('/learner/reviews/due')) return Promise.resolve(json([]))
      if (url.endsWith('/learner/reviews/summary')) {
        return Promise.resolve(
          json({
            totalCards: 0,
            dueCards: 0,
            masteredCards: 0,
            reviewsToday: 0,
            currentStreakDays: 0,
          }),
        )
      }
      if (url.endsWith('/learner/paths')) {
        return Promise.resolve(
          json([
            {
              courseId: 'course-1',
              title: 'Sales onboarding',
              description: 'Lộ trình nội bộ',
              cohortId: 'cohort-1',
              cohortName: 'Tháng 9',
              passingScorePercent: 70,
              requireDelayedRecall: false,
              completedLessons: 1,
              totalLessons: 1,
              certificateEligible: true,
              lessons: [
                {
                  lessonId: 'lesson-1',
                  assignmentId: 'assignment-1',
                  title: 'Nền tảng',
                  status: 'COMPLETED',
                  bestScorePercent: 90,
                  delayedRecallCompleted: false,
                  unlocked: true,
                },
              ],
            },
          ]),
        )
      }
      if (url.endsWith('/paths/course-1/cohorts/cohort-1/certificate')) {
        return Promise.resolve(
          json({
            id: 'certificate-1',
            courseId: 'course-1',
            cohortId: 'cohort-1',
            verificationCode: 'ABCDEF1234567890ABCD',
            learnerName: 'Nguyễn An',
            courseTitle: 'Sales onboarding',
            cohortName: 'Tháng 9',
            organizationName: 'Acme',
            issuedAt: '2026-09-06T00:00:00Z',
            revoked: false,
          }),
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    renderPage()

    await userEvent.click(
      await screen.findByRole('button', { name: 'Nhận certificate nội bộ' }),
    )
    expect(await screen.findByText(/ABCDEF1234567890ABCD/)).toBeInTheDocument()
    expect(
      screen.getByText(/không phải văn bằng\/chứng chỉ/),
    ).toBeInTheDocument()
  })
})
