import { supabase } from '../lib/supabase'

export interface ApiErrorBody {
  code?: string
  message?: string
  correlationId?: string
  fieldErrors?: Array<{ field: string; message: string }>
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly correlationId?: string,
  ) {
    super(message)
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await authenticatedFetch(path, init)
  if (!response.ok) {
    await throwApiError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export async function downloadApi(
  path: string,
  filename: string,
): Promise<void> {
  const response = await authenticatedFetch(path)
  if (!response.ok) {
    await throwApiError(response)
  }
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

async function authenticatedFetch(path: string, init: RequestInit = {}) {
  const session = supabase
    ? (await supabase.auth.getSession()).data.session
    : null
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (session?.access_token) {
    headers.set('Authorization', `Bearer ${session.access_token}`)
  }
  const response = await fetch(path, { ...init, headers })
  return response
}

async function throwApiError(response: Response): Promise<never> {
  const body = (await response.json().catch(() => ({}))) as ApiErrorBody
  throw new ApiError(
    body.message ?? 'Yêu cầu không thể hoàn tất.',
    response.status,
    body.code,
    body.correlationId,
  )
}

export function idempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}
