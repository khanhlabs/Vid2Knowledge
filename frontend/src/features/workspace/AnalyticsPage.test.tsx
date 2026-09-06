import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AnalyticsPage } from './AnalyticsPage'

const response = (value: unknown) =>
  new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('AnalyticsPage', () => {
  it('shows renewal-grade outcomes and cohort comparison', async () => {
    localStorage.setItem('v2k.organizationId', 'organization-1')
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      if (url.endsWith('/analytics/overview')) {
        return Promise.resolve(
          response({
            activeCohorts: 1,
            learners: 25,
            assigned: 20,
            started: 16,
            completed: 12,
            practiceAverageScorePercent: 78,
            delayedRecallAverageScorePercent: 70,
            feedbackResponses: 8,
            helpfulResponses: 7,
            reportedErrors: 1,
            openErrors: 1,
            activationRate: 0.8,
            completionRate: 0.6,
            timezone: 'Asia/Ho_Chi_Minh',
            generatedAt: '2026-09-06T00:00:00Z',
          }),
        )
      }
      return Promise.resolve(
        response([
          {
            id: 'cohort-1',
            name: 'Sales tháng 9',
            status: 'ACTIVE',
            learners: 25,
            assigned: 20,
            started: 16,
            completed: 12,
            averageScorePercent: 78,
            activationRate: 0.8,
            completionRate: 0.6,
          },
        ]),
      )
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <MemoryRouter>
        <QueryClientProvider client={client}>
          <AnalyticsPage />
        </QueryClientProvider>
      </MemoryRouter>,
    )

    expect(await screen.findAllByText('80%')).toHaveLength(2)
    expect(screen.getByText('70%')).toBeInTheDocument()
    expect(screen.getByText('Sales tháng 9')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Xuất CSV đối soát' }),
    ).toBeInTheDocument()
  })
})
