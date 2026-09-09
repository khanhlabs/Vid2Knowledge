import type { Session } from '@supabase/supabase-js'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  workspaceApi,
  type SourceUploadReservation,
} from '../../features/workspace/api'
import { api, downloadApi } from './client'
import {
  captureRequestScope,
  syncRequestIdentity,
  waitForRequestScope,
} from './request-scope'

const auth = vi.hoisted(() => ({ getSession: vi.fn() }))
vi.mock('../lib/supabase', () => ({ supabase: { auth } }))

function session(id: string) {
  return { user: { id }, access_token: `${id}-token` } as Session
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

beforeEach(() => {
  syncRequestIdentity('account-a')
  auth.getSession.mockResolvedValue({ data: { session: session('account-a') } })
})

afterEach(() => {
  syncRequestIdentity(null)
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it('does not dispatch a mutation when identity changes while retrieving its token', async () => {
  const pendingSession = deferred<{ data: { session: Session } }>()
  auth.getSession.mockReturnValue(pendingSession.promise)
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  const request = api('/api/v1/privacy/deletion-request', { method: 'POST' })
  const rejected = expect(request).rejects.toMatchObject({ name: 'AbortError' })
  syncRequestIdentity('account-b')
  pendingSession.resolve({ data: { session: session('account-b') } })
  await rejected
  expect(fetch).not.toHaveBeenCalled()
})

it('rejects a token belonging to a different identity before its auth event arrives', async () => {
  auth.getSession.mockResolvedValue({ data: { session: session('account-b') } })
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  await expect(api('/api/v1/me')).rejects.toMatchObject({ name: 'AbortError' })
  expect(fetch).not.toHaveBeenCalled()
})

it('aborts old requests and discards their delayed JSON even after the same account signs back in', async () => {
  const body = deferred<{ private: string }>()
  const reading = deferred<void>()
  const fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => {
      reading.resolve()
      return body.promise
    },
  })
  vi.stubGlobal('fetch', fetch)
  const request = api('/api/v1/me')
  const rejected = expect(request).rejects.toMatchObject({ name: 'AbortError' })
  await reading.promise
  syncRequestIdentity(null)
  syncRequestIdentity('account-a')
  const requestInit = fetch.mock.calls[0]?.[1] as RequestInit
  expect(requestInit.signal?.aborted).toBe(true)
  body.resolve({ private: 'old account data' })
  await rejected
})

it('never downloads a previous identity export when its blob finishes late', async () => {
  const body = deferred<Blob>()
  const reading = deferred<void>()
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      blob: () => {
        reading.resolve()
        return body.promise
      },
    }),
  )
  const click = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(() => {})
  const request = downloadApi('/api/v1/privacy/export', 'private-data.json')
  const rejected = expect(request).rejects.toMatchObject({ name: 'AbortError' })
  await reading.promise
  syncRequestIdentity('account-b')
  body.resolve(new Blob(['private data']))
  await rejected
  expect(click).not.toHaveBeenCalled()
})

it('preserves an in-flight request across a same-identity token refresh', async () => {
  const body = deferred<{ email: string }>()
  const reading = deferred<void>()
  const fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => {
      reading.resolve()
      return body.promise
    },
  })
  vi.stubGlobal('fetch', fetch)
  const request = api('/api/v1/me')
  await reading.promise
  syncRequestIdentity('account-a')
  body.resolve({ email: 'account-a@example.invalid' })
  await expect(request).resolves.toEqual({ email: 'account-a@example.invalid' })
  const requestInit = fetch.mock.calls[0]?.[1] as RequestInit
  expect(requestInit.signal?.aborted).toBe(false)
})

it('stops upload polling immediately on sign-out', async () => {
  vi.useFakeTimers()
  const scope = captureRequestScope()
  const wait = waitForRequestScope(scope, 2_500)
  const rejected = expect(wait).rejects.toMatchObject({ name: 'AbortError' })
  syncRequestIdentity(null)
  await rejected
  expect(vi.getTimerCount()).toBe(0)
})

it('aborts the active object-storage upload on an identity change', async () => {
  const request = {
    open: vi.fn(),
    setRequestHeader: vi.fn(),
    upload: {},
    send: vi.fn(),
    onabort: () => {},
    abort: vi.fn(() => request.onabort()),
  }
  vi.stubGlobal(
    'XMLHttpRequest',
    class {
      constructor() {
        return request
      }
    },
  )
  const scope = captureRequestScope()
  const upload = workspaceApi.putSourceUpload(
    {
      uploadUrl: 'https://storage.example.invalid/private',
      requiredHeaders: {},
    } as SourceUploadReservation,
    new File(['video'], 'video.mp4', { type: 'video/mp4' }),
    vi.fn(),
    scope.signal,
  )
  const rejected = expect(upload).rejects.toMatchObject({ name: 'AbortError' })
  expect(request.send).toHaveBeenCalledOnce()
  syncRequestIdentity('account-b')
  await rejected
  expect(request.abort).toHaveBeenCalledOnce()
})
