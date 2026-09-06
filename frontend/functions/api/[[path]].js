export async function onRequest(context) {
  const backendOrigin = context.env.BACKEND_ORIGIN
  if (!backendOrigin) {
    return new Response('Backend origin is not configured.', { status: 503 })
  }

  let backend
  try {
    backend = new URL(backendOrigin)
  } catch {
    return new Response('Backend origin is invalid.', { status: 503 })
  }

  if (backend.protocol !== 'https:') {
    return new Response('Backend origin must use HTTPS.', { status: 503 })
  }

  const incoming = new URL(context.request.url)
  const path = Array.isArray(context.params.path)
    ? context.params.path.join('/')
    : context.params.path
  const target = new URL(`/api/${path ?? ''}${incoming.search}`, backend)
  const headers = new Headers(context.request.headers)
  headers.delete('host')
  const method = context.request.method.toUpperCase()
  const upstreamRequest = new Request(target, {
    method,
    headers,
    body:
      method === 'GET' || method === 'HEAD'
        ? undefined
        : await context.request.arrayBuffer(),
    redirect: 'manual',
  })
  return fetch(upstreamRequest)
}
