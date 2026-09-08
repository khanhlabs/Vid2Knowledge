import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { LandingPage } from './LandingPage'

afterEach(cleanup)

describe('LandingPage', () => {
  it('positions the product for the paying training buyer', () => {
    render(
      <MemoryRouter>
        <LandingPage />
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('heading', {
        name: 'Biến thư viện video thành chương trình học có thể đo lường.',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText('Training Team')).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Bắt đầu trial 60 phút' }),
    ).toHaveAttribute('href', '/login')
    expect(
      screen.getByRole('link', { name: 'Xem khóa học mẫu' }),
    ).toHaveAttribute('href', '/sample')
    expect(
      screen.getByRole('link', { name: 'Đặt paid pilot' }),
    ).toHaveAttribute('href', '/pilot')
  })
})
