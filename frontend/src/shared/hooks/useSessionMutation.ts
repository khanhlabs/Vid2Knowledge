import { useState } from 'react'
import {
  useMutation,
  type DefaultError,
  type MutationFunction,
  type QueryClient,
  type UseMutationOptions,
} from '@tanstack/react-query'
import { captureRequestScope } from '../api/request-scope'

// Capture at render, not execution: an offline mutation can start after its
// original component and account have already left the page.
export function useSessionMutation<
  TData = unknown,
  TError = DefaultError,
  TVariables = void,
  TOnMutateResult = unknown,
>(
  options: UseMutationOptions<TData, TError, TVariables, TOnMutateResult> & {
    mutationFn: MutationFunction<TData, TVariables>
  },
  queryClient?: QueryClient,
) {
  const [scope] = useState(captureRequestScope)
  const onMutate = options.onMutate
  return useMutation<TData, TError, TVariables, TOnMutateResult>(
    {
      ...options,
      mutationFn: async (...args) => {
        scope.assertCurrent()
        const result = await options.mutationFn(...args)
        scope.assertCurrent()
        return result
      },
      onMutate: onMutate
        ? async (...args) => {
            scope.assertCurrent()
            const result = await onMutate(...args)
            scope.assertCurrent()
            return result
          }
        : undefined,
      onSuccess: (...args) => {
        if (!scope.signal.aborted) return options.onSuccess?.(...args)
      },
      onError: (...args) => {
        if (!scope.signal.aborted) return options.onError?.(...args)
      },
      onSettled: (...args) => {
        if (!scope.signal.aborted) return options.onSettled?.(...args)
      },
    },
    queryClient,
  )
}
