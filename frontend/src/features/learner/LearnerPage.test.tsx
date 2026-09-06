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
})
