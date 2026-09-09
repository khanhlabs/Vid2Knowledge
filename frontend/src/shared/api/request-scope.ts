export interface RequestScope {
  readonly identity: string | null
  readonly signal: AbortSignal
  assertCurrent(): void
}

let identity: string | null = null
let controller = new AbortController()

export function syncRequestIdentity(nextIdentity: string | null) {
  if (nextIdentity === identity) return
  controller.abort()
  identity = nextIdentity
  controller = new AbortController()
}

export function captureRequestScope(): RequestScope {
  const capturedController = controller
  return {
    identity,
    signal: capturedController.signal,
    assertCurrent() {
      capturedController.signal.throwIfAborted()
    },
  }
}

export function waitForRequestScope(scope: RequestScope, delayMs: number) {
  return new Promise<void>((resolve, reject) => {
    scope.assertCurrent()
    const cancel = () => {
      window.clearTimeout(timer)
      reject(new DOMException('Phiên đăng nhập đã thay đổi.', 'AbortError'))
    }
    const timer = window.setTimeout(() => {
      scope.signal.removeEventListener('abort', cancel)
      resolve()
    }, delayMs)
    scope.signal.addEventListener('abort', cancel, { once: true })
  })
}
