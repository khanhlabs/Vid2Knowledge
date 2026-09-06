import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PreviewPage } from './PreviewPage'

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <PreviewPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('PreviewPage', () => {
  it('rejects a non-YouTube URL before calling the API', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    renderPage()

    await userEvent.type(
      screen.getByLabelText('Đường dẫn video YouTube'),
      'https://example.com/video',
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Tạo bản xem trước' }),
    )

    expect(
      await screen.findByText(
        'Hiện tại Vid2Knowledge chỉ hỗ trợ video YouTube.',
      ),
    ).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('renders the generated package summary', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          video: {
            youtubeUrl: 'https://www.youtube.com/watch?v=abcdefghijk',
            title: 'Bài học thử',
            language: 'vi',
          },
          summary: {
            overview: 'Tổng quan bài học',
            sections: [{ title: 'Mở đầu', content: ['Nội dung'] }],
          },
          keyTakeaways: ['Ý chính đầu tiên'],
          flashcards: Array.from({ length: 10 }, (_, index) => ({
            question: `Q${index}`,
            answer: `A${index}`,
          })),
          quiz: Array.from({ length: 5 }, (_, index) => ({
            question: `Q${index}`,
            options: ['A', 'B', 'C', 'D'],
            correctAnswerIndex: 0,
            explanation: 'Vì vậy',
          })),
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    renderPage()

    await userEvent.type(
      screen.getByLabelText('Đường dẫn video YouTube'),
      'https://youtu.be/abcdefghijk',
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Tạo bản xem trước' }),
    )

    expect(
      await screen.findByRole('heading', { name: 'Bài học thử' }),
    ).toBeInTheDocument()
    expect(screen.getByText('10 flashcard')).toBeInTheDocument()
  })
})
