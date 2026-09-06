import { afterEach, describe, expect, it, vi } from 'vitest'

import { onRequest } from './[[path]].js'

describe('Cloudflare API proxy', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('forwards method, authorization, body, path, and query to the configured backend', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    const response = await onRequest({
      env: { BACKEND_ORIGIN: 'https://api.example.com' },
      params: { path: ['v1', 'courses'] },
      request: new Request('https://app.example.com/api/v1/courses?page=2', {
        method: 'POST',
        headers: {
          authorization: 'Bearer token',
          'content-type': 'application/json',
        },
        body: JSON.stringify({ title: 'Kinh tế vi mô' }),
      }),
    })

    expect(response.status).toBe(204)
    expect(fetchMock).toHaveBeenCalledOnce()
    const forwarded = fetchMock.mock.calls[0][0]
    expect(forwarded.url).toBe('https://api.example.com/api/v1/courses?page=2')
    expect(forwarded.method).toBe('POST')
    expect(forwarded.headers.get('authorization')).toBe('Bearer token')
    expect(await forwarded.json()).toEqual({ title: 'Kinh tế vi mô' })
  })

  it('fails closed when the backend origin is absent or insecure', async () => {
    const request = new Request('https://app.example.com/api/v1/courses')

    expect(
      (await onRequest({ env: {}, params: { path: 'v1/courses' }, request }))
        .status,
    ).toBe(503)
    expect(
      (
        await onRequest({
          env: { BACKEND_ORIGIN: 'http://api.example.com' },
          params: { path: 'v1/courses' },
          request,
        })
      ).status,
    ).toBe(503)
  })
})
