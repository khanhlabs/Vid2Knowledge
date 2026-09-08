import { test, expect, type BrowserContext, type Page } from '@playwright/test'

interface TestSession {
  access_token: string
  user: { email: string }
}

async function signIn(context: BrowserContext, rawSession: string) {
  await context.addInitScript((session) => {
    localStorage.setItem('sb-e2e-auth-token', session)
  }, rawSession)
  // All third-party work is mocked by the backend's test-only providers.
  await context.route('https://**/*', (route) => route.abort())
}

async function acceptPolicies(page: Page) {
  await expect(
    page.getByRole('heading', { name: 'Đọc và chấp nhận trước khi tiếp tục.' }),
  ).toBeVisible()
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: 'Đồng ý và tiếp tục' }).click()
}

test('owner publishes a course, invited learner completes it, and tenant boundaries hold', async ({
  browser,
  page,
  context,
}) => {
  const ownerRaw = process.env.V2K_E2E_OWNER_SESSION!
  const learnerRaw = process.env.V2K_E2E_LEARNER_SESSION!
  const owner = JSON.parse(ownerRaw) as TestSession
  const learner = JSON.parse(learnerRaw) as TestSession
  const runtimeErrors: string[] = []
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  await signIn(context, ownerRaw)
  await page.goto('/app')
  await acceptPolicies(page)
  await page.getByLabel('Tên tổ chức', { exact: true }).fill('Browser Academy')
  const organizationResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/organizations') &&
      response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Tạo workspace', exact: true }).click()
  const organization = (await (await organizationResponse).json()) as {
    id: string
  }
  expect(organization.id).toBeTruthy()

  await page
    .getByLabel('URL YouTube', { exact: true })
    .fill('https://www.youtube.com/watch?v=abcdefghijk')
  await page
    .getByRole('checkbox', { name: /Tôi xác nhận tổ chức có quyền dùng video/ })
    .check()
  await page
    .getByRole('button', { name: 'Tạo bản nháp có kiểm duyệt', exact: true })
    .click()
  await page
    .getByRole('link', { name: 'Mở bản nháp để kiểm duyệt →', exact: true })
    .click({ timeout: 30_000 })
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Browser training',
  )
  for (const action of ['Gửi kiểm duyệt', 'Phê duyệt', 'Xuất bản']) {
    await page.getByRole('button', { name: action, exact: true }).click()
  }
  await expect(
    page.getByRole('button', { name: 'Lưu trữ', exact: true }),
  ).toBeVisible()

  await page.getByRole('link', { name: '← Workspace', exact: true }).click()
  await page
    .getByLabel('Email người nhận', { exact: true })
    .fill(learner.user.email)
  await page.getByRole('button', { name: 'Tạo link mời', exact: true }).click()
  const invitation = page.locator('.invite-link span')
  await expect(invitation).toContainText('/accept-invitation?token=')
  const invitationUrl = await invitation.innerText()
  const learnerContext = await browser.newContext({
    baseURL: 'http://127.0.0.1:4174',
  })
  try {
    await signIn(learnerContext, learnerRaw)
    const learnerPage = await learnerContext.newPage()
    learnerPage.on('pageerror', (error) => runtimeErrors.push(error.message))
    await learnerPage.goto(invitationUrl)
    await acceptPolicies(learnerPage)
    await expect(learnerPage).toHaveURL(/\/app$/)

    await page.goto('/app/catalog')
    await page
      .getByLabel('Tên chương trình', { exact: true })
      .fill('Browser cohort')
    await page
      .getByLabel('Học liệu đã xuất bản', { exact: true })
      .selectOption({ label: 'Browser training' })
    await page
      .getByRole('checkbox', {
        name: new RegExp(
          learner.user.email.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
        ),
      })
      .check()
    await page
      .getByRole('button', { name: 'Khởi chạy và giao bài', exact: true })
      .click()
    await expect(
      page.getByText('Chương trình đã được publish và giao thành công.', {
        exact: true,
      }),
    ).toBeVisible()

    await learnerPage.goto(`/learn/${organization.id}`)
    await learnerPage
      .getByRole('button', { name: 'Bắt đầu', exact: true })
      .click()
    const assessmentResponse = learnerPage.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/assessments'),
    )
    await learnerPage
      .getByRole('button', { name: 'Bắt đầu đề luyện mới', exact: true })
      .click()
    const assessmentBody = await (await assessmentResponse).text()
    expect(assessmentBody).not.toMatch(
      /correctAnswerIndex|answerKey|correct_answer/,
    )
    await expect(
      learnerPage.getByRole('radio', { name: 'A', exact: true }),
    ).toHaveCount(5)
    for (const option of await learnerPage
      .getByRole('radio', { name: 'A', exact: true })
      .all())
      await option.check()
    await learnerPage
      .getByRole('button', { name: 'Nộp bài', exact: true })
      .click()
    await expect(
      learnerPage.getByText('5/5 câu đúng', { exact: true }),
    ).toBeVisible()
    await learnerPage.reload()
    await expect(learnerPage.getByText(/Điểm cao nhất 100%/)).toBeVisible()

    const otherOrgResponse = await page.request.post('/api/v1/organizations', {
      headers: { Authorization: `Bearer ${owner.access_token}` },
      data: { name: 'Private second academy', slug: 'private-second-academy' },
    })
    expect(otherOrgResponse.status()).toBe(201)
    const otherOrg = (await otherOrgResponse.json()) as { id: string }
    const forbidden = await learnerPage.request.get(
      `/api/v1/organizations/${otherOrg.id}/members`,
      {
        headers: { Authorization: `Bearer ${learner.access_token}` },
      },
    )
    expect(forbidden.status()).toBe(403)
    const forbiddenOwnMembers = await learnerPage.request.get(
      `/api/v1/organizations/${organization.id}/members`,
      {
        headers: { Authorization: `Bearer ${learner.access_token}` },
      },
    )
    expect(forbiddenOwnMembers.status()).toBe(403)
    expect(runtimeErrors).toEqual([])
  } finally {
    await learnerContext.close()
  }
})
