import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PackageReviewPage } from './PackageReviewPage'

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('PackageReviewPage', () => {
  it('requires and submits an actionable rejection reason', async () => {
    localStorage.setItem('v2k.organizationId', 'organization-1')
    const requests: RequestInit[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => {
      if (init?.method === 'POST') requests.push(init)
      return Promise.resolve(
        new Response(
          JSON.stringify({
            id: 'package-1',
            sourceId: 'source-1',
            state: init?.method === 'POST' ? 'REJECTED' : 'IN_REVIEW',
            version: 1,
            revisionId: 'revision-1',
            revisionNo: 1,
            verificationState: 'UNVERIFIED',
            content: { video: { title: 'An toàn bán hàng' } },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <MemoryRouter initialEntries={['/app/packages/package-1']}>
        <QueryClientProvider client={client}>
          <Routes>
            <Route
              path="/app/packages/:packageId"
              element={<PackageReviewPage />}
            />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>,
    )

    const reject = await screen.findByRole('button', {
      name: 'Yêu cầu chỉnh sửa',
    })
    expect(reject).toBeDisabled()
    await userEvent.type(
      screen.getByLabelText('Lý do yêu cầu chỉnh sửa'),
      'Sai timestamp ở câu quiz 2',
    )
    await userEvent.click(reject)

    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]?.body).toBe(
      JSON.stringify({ reason: 'Sai timestamp ở câu quiz 2' }),
    )
  })

  it('saves a structured edit as a version-guarded revision', async () => {
    localStorage.setItem('v2k.organizationId', 'organization-1')
    const source = { timestampSeconds: 12, evidence: 'Bằng chứng gốc' }
    const content = {
      schemaVersion: '1.0',
      video: {
        youtubeUrl: 'https://www.youtube.com/watch?v=abcdefghijk',
        videoId: 'abcdefghijk',
        title: 'Tiêu đề ban đầu',
        language: 'vi',
      },
      summary: {
        overview: 'Tổng quan',
        sections: [
          { id: 'section-one', title: 'Phần một', source, content: ['Đoạn'] },
        ],
      },
      keyTakeaways: [{ id: 'takeaway-one', text: 'Ý chính', source }],
      flashcards: Array.from({ length: 10 }, (_, index) => ({
        id: `card-${index + 1}`,
        question: `Câu hỏi ${index + 1}`,
        answer: `Trả lời ${index + 1}`,
        source,
      })),
      quiz: Array.from({ length: 5 }, (_, index) => ({
        id: `quiz-${index + 1}`,
        question: `Quiz ${index + 1}`,
        options: ['A', 'B', 'C', 'D'],
        correctAnswerIndex: 0,
        explanation: 'Giải thích',
        source,
      })),
    }
    const patches: RequestInit[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      if (url.endsWith('/api/v1/me')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              organizations: [{ id: 'organization-1', role: 'INSTRUCTOR' }],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        )
      }
      let responseContent = content
      if (init?.method === 'PATCH') {
        patches.push(init)
        if (typeof init.body !== 'string') throw new Error('Expected JSON body')
        responseContent = (JSON.parse(init.body) as { content: typeof content })
          .content
      }
      return Promise.resolve(
        new Response(
          JSON.stringify({
            id: 'package-1',
            sourceId: 'source-1',
            state: init?.method === 'PATCH' ? 'DRAFT' : 'GENERATED',
            version: init?.method === 'PATCH' ? 8 : 7,
            revisionId: 'revision-1',
            revisionNo: init?.method === 'PATCH' ? 2 : 1,
            verificationState: 'UNVERIFIED',
            content: responseContent,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <MemoryRouter initialEntries={['/app/packages/package-1']}>
        <QueryClientProvider client={client}>
          <Routes>
            <Route
              path="/app/packages/:packageId"
              element={<PackageReviewPage />}
            />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>,
    )

    const title = await screen.findByLabelText('Tiêu đề học liệu')
    await userEvent.clear(title)
    await userEvent.type(title, 'Kỹ năng bán hàng đã duyệt')
    await userEvent.click(
      screen.getByRole('button', { name: 'Lưu revision mới' }),
    )

    await waitFor(() => expect(patches).toHaveLength(1))
    expect(new Headers(patches[0]?.headers).get('If-Match')).toBe('"7"')
    const body = patches[0]?.body
    if (typeof body !== 'string') throw new Error('Expected JSON request body')
    expect(
      (JSON.parse(body) as { content: typeof content }).content.video.title,
    ).toBe('Kỹ năng bán hàng đã duyệt')
    expect(await screen.findByText('Revision 2 đã đồng bộ.')).toBeVisible()
  })
})
