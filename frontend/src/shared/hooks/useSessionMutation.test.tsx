import {
  QueryClient,
  QueryClientProvider,
  onlineManager,
} from '@tanstack/react-query'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { syncRequestIdentity } from '../api/request-scope'
import { useSessionMutation } from './useSessionMutation'

afterEach(() => {
  cleanup()
  syncRequestIdentity(null)
  onlineManager.setOnline(true)
})

it('never executes an old account mutation paused offline after reconnecting as another account', async () => {
  syncRequestIdentity('owner-a')
  onlineManager.setOnline(false)
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const mutationFn = vi.fn(() => Promise.resolve('private result'))
  const onSuccess = vi.fn()
  const { result, unmount } = renderHook(
    () => useSessionMutation({ mutationFn, onSuccess }),
    {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    },
  )
  act(() => result.current.mutate())
  await waitFor(() => expect(result.current.isPaused).toBe(true))
  const paused = client.getMutationCache().getAll()[0]!
  unmount()
  client.clear()
  syncRequestIdentity('learner-b')
  onlineManager.setOnline(true)
  // Explicitly resume the retained mutation; cache clearing alone cannot cancel it.
  await act(() =>
    expect(paused.continue()).rejects.toMatchObject({ name: 'AbortError' }),
  )
  expect(mutationFn).not.toHaveBeenCalled()
  expect(onSuccess).not.toHaveBeenCalled()
})

it('discards completed mutations and their lifecycle effects after an identity switch', async () => {
  syncRequestIdentity('owner-a')
  let complete!: (value: string) => void
  const mutationFn = vi.fn(
    () =>
      new Promise<string>((resolve) => {
        complete = resolve
      }),
  )
  const onSuccess = vi.fn()
  const onError = vi.fn()
  const onSettled = vi.fn()
  const client = new QueryClient()
  const { result } = renderHook(
    () => useSessionMutation({ mutationFn, onSuccess, onError, onSettled }),
    {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    },
  )
  act(() => result.current.mutate())
  await waitFor(() => expect(mutationFn).toHaveBeenCalledOnce())
  act(() => {
    syncRequestIdentity('learner-b')
    complete('owner data')
  })
  await waitFor(() => expect(result.current.isError).toBe(true))
  expect(onSuccess).not.toHaveBeenCalled()
  expect(onError).not.toHaveBeenCalled()
  expect(onSettled).not.toHaveBeenCalled()
  client.clear()
})
