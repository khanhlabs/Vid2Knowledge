import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { IntegrationsPage } from './IntegrationsPage'

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('IntegrationsPage', () => {
  it('creates a scoped key and reveals its token exactly in the success state', async () => {
    localStorage.setItem('v2k.organizationId', 'organization-1')
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      if (url.endsWith('/api/v1/me')) {
        return Promise.resolve(
          json({
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
      if (url.endsWith('/integrations/api-keys') && init?.method === 'POST') {
        return Promise.resolve(
          json(
            {
              id: 'key-1',
              name: 'BI export',
              tokenPrefix: 'v2k_live_prefix',
              scopes: ['analytics:read'],
              expiresAt: '2026-10-07T00:00:00Z',
              lastUsedAt: null,
              revokedAt: null,
              token: 'v2k_live_one_time_secret',
              createdAt: '2026-09-07T00:00:00Z',
            },
            201,
          ),
        )
      }
      if (url.endsWith('/integrations/api-keys'))
        return Promise.resolve(json([]))
      if (url.endsWith('/integrations/webhook-endpoints'))
        return Promise.resolve(json([]))
      throw new Error(`Unexpected request ${url}`)
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <MemoryRouter>
        <QueryClientProvider client={client}>
          <IntegrationsPage />
        </QueryClientProvider>
      </MemoryRouter>,
    )

    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Tên key'), 'BI export')
    await user.click(screen.getByRole('button', { name: 'Tạo key 30 ngày' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'v2k_live_one_time_secret',
    )
    const request = vi
      .mocked(globalThis.fetch)
      .mock.calls.find(([, options]) => Boolean(options?.method === 'POST'))
    expect(request?.[1]?.body).toContain('analytics:read')
  })
})
