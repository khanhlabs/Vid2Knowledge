import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SampleCoursePage } from './SampleCoursePage'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('SampleCoursePage', () => {
  it('demonstrates learning value without calling an API or inflating product usage', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    render(
      <MemoryRouter>
        <SampleCoursePage />
      </MemoryRouter>,
    )

    expect(
      screen.getByText('Không tính activation hoặc usage'),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Hiện đáp án' }))
    expect(
      screen.getByText(/Mức độ gián đoạn và số người bị ảnh hưởng/),
    ).toBeInTheDocument()

    await userEvent.click(
      screen.getByLabelText(
        'Xác nhận vấn đề, tác động và mốc phản hồi kiểm soát được',
      ),
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Kiểm tra đáp án' }),
    )
    expect(screen.getByRole('status')).toHaveTextContent('Chính xác')
    expect(fetchSpy).not.toHaveBeenCalled()
    expect(
      screen.getByRole('link', { name: 'Bắt đầu trial 60 phút' }),
    ).toHaveAttribute('href', '/login?source=sample-course')
  })
})
