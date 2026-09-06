import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PackageReviewPage } from './PackageReviewPage'

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('PackageReviewPage', () => {
  it('requires and submits an actionable rejection reason', async () => {
    localStorage.setItem('v2k.organizationId', 'organization-1')
    const requests: RequestInit[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => {
      if (init?.method === 'POST') requests.push(init)
      return Promise.resolve(
        new Response(
          JSON.stringify({
            id: 'package-1',
            sourceId: 'source-1',
            state: init?.method === 'POST' ? 'REJECTED' : 'IN_REVIEW',
            version: 1,
            revisionId: 'revision-1',
            revisionNo: 1,
            verificationState: 'UNVERIFIED',
            content: { video: { title: 'An toàn bán hàng' } },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <MemoryRouter initialEntries={['/app/packages/package-1']}>
        <QueryClientProvider client={client}>
          <Routes>
            <Route
              path="/app/packages/:packageId"
              element={<PackageReviewPage />}
            />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>,
    )

    const reject = await screen.findByRole('button', {
      name: 'Yêu cầu chỉnh sửa',
    })
    expect(reject).toBeDisabled()
    await userEvent.type(
      screen.getByLabelText('Lý do yêu cầu chỉnh sửa'),
      'Sai timestamp ở câu quiz 2',
    )
    await userEvent.click(reject)

    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]?.body).toBe(
      JSON.stringify({ reason: 'Sai timestamp ở câu quiz 2' }),
    )
  })
})
