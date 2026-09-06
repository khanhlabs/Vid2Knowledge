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
      if (url.endsWith('/api/v1/me')) {
        return Promise.resolve(
          response({
            id: 'user-1',
            email: 'owner@example.com',
            displayName: 'Owner',
            organizations: [
              {
                id: 'organization-1',
                name: 'Acme',
                slug: 'acme',
                role: 'OWNER',
              },
            ],
          }),
        )
      }
      if (url.endsWith('/analytics/profitability/assumptions')) {
        return Promise.resolve(
          response({
            usdVndRate: 26000,
            paymentFeeBps: 150,
            paymentFixedFeeVnd: 0,
            monthlyInfrastructureVnd: 100000,
            monthlySupportMinutes: 120,
            supportHourlyVnd: 200000,
            taxReserveBps: 1000,
            acquisitionCostVnd: 1000000,
            monthlyLogoChurnBps: 300,
            assumptionsConfirmed: true,
          }),
        )
      }
      if (url.endsWith('/analytics/profitability')) {
        return Promise.resolve(
          response({
            assumptionsConfirmed: true,
            status: 'HEALTHY',
            grossCashVnd: 790000,
            netCashVnd: 790000,
            refundsVnd: 0,
            recognizedRevenueVnd: 790000,
            actualAiCostVnd: 0,
            shadowAiCostVnd: 40000,
            paymentFeesVnd: 11850,
            allocatedInfrastructureVnd: 100000,
            modeledSupportVnd: 400000,
            manualDirectCostsVnd: 0,
            taxReserveVnd: 79000,
            grossProfitVnd: 638150,
            contributionProfitVnd: 238150,
            grossMargin: 0.8078,
            contributionMargin: 0.3015,
            shadowAiRevenueShare: 0.0506,
            cacPaybackMonths: 5,
            monthlyContributionVnd: 238150,
            contributionLtvVnd: 7938333,
            ltvCacRatio: 7.94,
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
    expect(await screen.findByText('Biên lợi nhuận tốt')).toBeInTheDocument()
    expect(screen.getByText('7.9×')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Xuất CSV đối soát' }),
    ).toBeInTheDocument()
  })
})
