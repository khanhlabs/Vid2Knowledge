import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { useAuth } from './auth-context'

export function SessionQueryProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth()
  return (
    <IdentityQueries key={session?.user.id ?? 'signed-out'}>
      {children}
    </IdentityQueries>
  )
}

function IdentityQueries({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: 1, staleTime: 30_000 },
          mutations: { retry: false },
        },
      }),
  )
  useEffect(() => () => client.clear(), [client])
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}
