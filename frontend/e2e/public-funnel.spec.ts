import { test, expect } from '@playwright/test'

test('sample lesson works without creating usage or learner progress', async ({
  page,
}) => {
  const apiRequests: string[] = []
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/'))
      apiRequests.push(request.url())
  })
  await page.goto('/')
  await page
    .getByRole('link', { name: 'Xem khóa học mẫu', exact: true })
    .click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Tiếp nhận và xử lý phản hồi khách hàng',
  )
  await page.getByRole('button', { name: 'Hiện đáp án', exact: true }).click()
  await expect(
    page.getByText(
      'Mức độ gián đoạn và số người bị ảnh hưởng, thay vì chỉ dựa vào mức độ bức xúc.',
      { exact: true },
    ),
  ).toBeVisible()
  const grade = page.getByRole('button', {
    name: 'Kiểm tra đáp án',
    exact: true,
  })
  await expect(grade).toBeDisabled()
  await page
    .getByRole('radio', {
      name: 'Xác nhận vấn đề, tác động và mốc phản hồi kiểm soát được',
      exact: true,
    })
    .check()
  await grade.click()
  await expect(page.getByRole('status')).toContainText('Chính xác.')
  await page
    .getByRole('link', { name: 'Dùng video của đội ngũ', exact: true })
    .click()
  await expect(page).toHaveURL(/\/login\?source=sample-course$/)
  expect(apiRequests).toEqual([])
})

test('pilot capture keeps attribution, consent and retry identity', async ({
  page,
}) => {
  const requests: { key: string | undefined; body: Record<string, unknown> }[] =
    []
  await page.route('**/api/v1/public/pilot-leads', async (route) => {
    requests.push({
      key: route.request().headers()['idempotency-key'],
      body: route.request().postDataJSON() as Record<string, unknown>,
    })
    await route.fulfill({
      status: requests.length === 1 ? 503 : 201,
      json:
        requests.length === 1
          ? { message: 'Temporary failure' }
          : { id: 'smoke-lead', receivedAt: '2026-09-08T00:00:00Z' },
    })
  })
  await page.goto('/pilot?source=partner&campaign=academy-smoke')
  await page.getByLabel('Họ tên', { exact: true }).fill('Smoke Test')
  await page
    .getByLabel('Email công việc', { exact: true })
    .fill('smoke@example.invalid')
  await page
    .getByLabel('Tổ chức / học viện', { exact: true })
    .fill('Smoke Academy')
  await page
    .getByRole('combobox', { name: 'Vai trò', exact: true })
    .selectOption('OWNER')
  const submit = page.getByRole('button', {
    name: 'Gửi yêu cầu pilot',
    exact: true,
  })
  await submit.click()
  expect(requests).toHaveLength(0)
  await page.getByRole('checkbox').check()
  await submit.click()
  await expect(page.getByRole('alert')).toContainText('Chưa gửi được yêu cầu.')
  await submit.click()
  await expect(page.getByRole('status')).toContainText(
    'Yêu cầu đã được ghi nhận.',
  )
  await expect(
    page.getByRole('button', { name: 'Đã nhận yêu cầu', exact: true }),
  ).toBeDisabled()
  expect(requests).toHaveLength(2)
  expect(requests[0]?.key).toMatch(/^pilot-lead-/)
  expect(requests[1]?.key).toBe(requests[0]?.key)
  expect(requests[1]?.body).toMatchObject({
    acquisitionSource: 'PARTNER',
    acquisitionCampaign: 'academy-smoke',
    contactConsent: true,
    buyerRole: 'OWNER',
  })
})

test('signed-out users cannot open workspace or learner data', async ({
  page,
}) => {
  for (const path of ['/app', '/app/analytics', '/learn/smoke-organization']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/login$/)
    await expect(
      page.getByRole('heading', {
        name: 'Đăng nhập để tiếp tục.',
        exact: true,
      }),
    ).toBeVisible()
  }
})
