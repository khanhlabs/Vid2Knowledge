import type { Session } from '@supabase/supabase-js'
import {
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { AuthContext } from './auth-context'
import { SessionQueryProvider } from './SessionQueryProvider'
import { packageDraftKey, syncStoredIdentity } from './identity-storage'

afterEach(() => {
  cleanup()
  localStorage.clear()
})

it('reuses cache for token refresh but never across identities', async () => {
  const clients: QueryClient[] = []
  function Probe({ label }: { label: string }) {
    const client = useQueryClient()
    if (!clients.includes(client)) clients.push(client)
    const query = useQuery({
      queryKey: ['me'],
      queryFn: () => Promise.resolve(label),
      staleTime: Infinity,
    })
    return <span>{query.data ?? 'Loading'}</span>
  }
  const tree = (id: string, label: string) => (
    <AuthContext.Provider
      value={{
        session: { user: { id } } as Session,
        loading: false,
        configured: true,
        signOut: () => Promise.resolve(),
      }}
    >
      <SessionQueryProvider>
        <Probe label={label} />
      </SessionQueryProvider>
    </AuthContext.Provider>
  )
  const view = render(tree('owner-a', 'Owner A private data'))
  await screen.findByText('Owner A private data')
  view.rerender(tree('owner-a', 'Refreshed token'))
  expect(screen.getByText('Owner A private data')).toBeInTheDocument()
  expect(clients).toHaveLength(1)
  view.rerender(tree('learner-b', 'Learner B data'))
  expect(screen.queryByText('Owner A private data')).not.toBeInTheDocument()
  await screen.findByText('Learner B data')
  expect(clients).toHaveLength(2)
  expect(clients[0]?.getQueryData(['me'])).toBeUndefined()
})

it('resets organization selection and isolates drafts without deleting unsaved work', () => {
  syncStoredIdentity('owner-a')
  localStorage.setItem('v2k.organizationId', 'private-org')
  const ownerDraft = packageDraftKey('owner-a', 'private-org', 'package')
  localStorage.setItem(ownerDraft, 'Owner unsaved work')
  syncStoredIdentity('owner-a')
  expect(localStorage.getItem('v2k.organizationId')).toBe('private-org')
  syncStoredIdentity('learner-b')
  expect(localStorage.getItem('v2k.organizationId')).toBeNull()
  expect(
    localStorage.getItem(
      packageDraftKey('learner-b', 'private-org', 'package'),
    ),
  ).toBeNull()
  expect(localStorage.getItem(ownerDraft)).toBe('Owner unsaved work')
  syncStoredIdentity(null)
  expect(localStorage.getItem('v2k.storageIdentity')).toBeNull()
  syncStoredIdentity('owner-a')
  expect(
    localStorage.getItem(packageDraftKey('owner-a', 'private-org', 'package')),
  ).toBe('Owner unsaved work')
})
