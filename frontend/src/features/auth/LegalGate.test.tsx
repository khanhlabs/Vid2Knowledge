import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LegalGate } from './LegalGate'

const manifest = {
  policySetVersion: 'v1',
  reviewed: true,
  policies: [
    { type: 'TERMS', version: 't1', url: 'https://example.com/terms' },
    { type: 'PRIVACY', version: 'p1', url: 'https://example.com/privacy' },
    {
      type: 'ACCEPTABLE_USE',
      version: 'a1',
      url: 'https://example.com/aup',
    },
    { type: 'AI_NOTICE', version: 'ai1', url: 'https://example.com/ai' },
  ],
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('LegalGate', () => {
  it('blocks the app until every current policy version is explicitly accepted', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation((input, init) => {
        const url =
          typeof input === 'string'
            ? input
            : input instanceof URL
              ? input.href
              : input.url
        const accepted = url.endsWith('/acceptances') && init?.method === 'POST'
        return Promise.resolve(
          new Response(JSON.stringify({ accepted, manifest }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      })
    const client = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    render(
      <QueryClientProvider client={client}>
        <LegalGate identityKey="user-1">
          <p>Nội dung ứng dụng</p>
        </LegalGate>
      </QueryClientProvider>,
    )

    const accept = await screen.findByRole('button', {
      name: 'Đồng ý và tiếp tục',
    })
    expect(accept).toBeDisabled()
    expect(screen.queryByText('Nội dung ứng dụng')).not.toBeInTheDocument()
    await userEvent.click(
      screen.getByRole('checkbox', { name: /Tôi đã mở, đọc/ }),
    )
    await userEvent.click(accept)

    expect(await screen.findByText('Nội dung ứng dụng')).toBeInTheDocument()
    const request = fetchMock.mock.calls.find(([input]) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      return url.endsWith('/api/v1/legal/acceptances')
    })
    const requestBody = request?.[1]?.body
    expect(typeof requestBody).toBe('string')
    if (typeof requestBody !== 'string')
      throw new Error('Missing acceptance body')
    expect(JSON.parse(requestBody)).toMatchObject({
      policySetVersion: 'v1',
      termsVersion: 't1',
      privacyVersion: 'p1',
      acceptableUseVersion: 'a1',
      aiNoticeVersion: 'ai1',
      confirmed: true,
    })
  })
})
