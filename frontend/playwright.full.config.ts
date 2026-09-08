import { defineConfig, devices } from '@playwright/test'

if (
  !process.env.V2K_E2E_BACKEND_URL ||
  !process.env.V2K_E2E_OWNER_SESSION ||
  !process.env.V2K_E2E_LEARNER_SESSION
) {
  throw new Error(
    'Run this suite through Maven -Pbrowser-e2e so its isolated backend and test identities exist',
  )
}

export default defineConfig({
  testDir: './e2e/full-stack',
  workers: 1,
  retries: 0,
  timeout: 120_000,
  forbidOnly: Boolean(process.env.CI),
  reporter: [['list']],
  outputDir: 'test-results/full-stack',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:4174',
    serviceWorkers: 'block',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 15_000,
    // Even test credentials need not be retained in HTTP traces.
    trace: 'off',
  },
  webServer: {
    command:
      'npm run build -- --outDir dist-journey && npm run preview -- --outDir dist-journey --host 127.0.0.1 --port 4174 --strictPort',
    url: 'http://127.0.0.1:4174',
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      VITE_SUPABASE_URL: 'https://e2e.invalid',
      VITE_SUPABASE_ANON_KEY: 'browser-test-public-key',
    },
  },
})
