import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { pilotLeadApi } from './api'
import { PilotPage } from './PilotPage'

vi.mock('./api', () => ({
  pilotLeadApi: {
    newKey: vi.fn(() => 'pilot-lead-00000000-0000-4000-8000-000000000001'),
    submit: vi.fn(),
  },
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('PilotPage', () => {
  it('captures qualification and explicit contact consent with one idempotency key', async () => {
    vi.mocked(pilotLeadApi.submit).mockResolvedValue({
      id: 'lead-1',
      receivedAt: '2026-09-07T00:00:00Z',
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter
        initialEntries={['/pilot?source=PARTNER&campaign=trainer-network']}
      >
        <PilotPage />
      </MemoryRouter>,
    )

    await user.type(screen.getByLabelText('Họ tên'), 'Nguyễn Minh')
    await user.type(screen.getByLabelText('Email công việc'), 'minh@example.vn')
    await user.type(
      screen.getByLabelText('Tổ chức / học viện'),
      'Học viện Minh',
    )
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu pilot' }))

    await waitFor(() => expect(pilotLeadApi.submit).toHaveBeenCalledTimes(1))
    expect(pilotLeadApi.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        contactName: 'Nguyễn Minh',
        acquisitionSource: 'PARTNER',
        acquisitionCampaign: 'trainer-network',
        contactConsent: true,
      }),
      'pilot-lead-00000000-0000-4000-8000-000000000001',
    )
    expect(screen.getByRole('status')).toHaveTextContent(
      'Yêu cầu đã được ghi nhận',
    )
  })

  it('drops untrusted campaign text instead of sending raw URL attribution', async () => {
    vi.mocked(pilotLeadApi.submit).mockResolvedValue({
      id: 'lead-2',
      receivedAt: '2026-09-07T00:00:00Z',
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter
        initialEntries={[
          '/pilot?source=PARTNER&campaign=https://tracker.example/unsafe?email=x',
        ]}
      >
        <PilotPage />
      </MemoryRouter>,
    )

    await user.type(screen.getByLabelText('Họ tên'), 'Nguyễn Minh')
    await user.type(screen.getByLabelText('Email công việc'), 'minh@example.vn')
    await user.type(
      screen.getByLabelText('Tổ chức / học viện'),
      'Học viện Minh',
    )
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu pilot' }))

    await waitFor(() => expect(pilotLeadApi.submit).toHaveBeenCalledTimes(1))
    expect(pilotLeadApi.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        acquisitionSource: 'PARTNER',
        acquisitionCampaign: undefined,
      }),
      expect.any(String),
    )
  })
})
