# Production infrastructure

The Terraform stack provisions the low-idle-cost GCP half of Vid2Knowledge: two scale-to-zero Cloud Run services, a rate-limited Cloud Tasks queue, OIDC-authenticated outbox/billing/notification schedules, Artifact Registry, least-privilege service accounts, and Secret Manager containers. Supabase hosts PostgreSQL/Auth and Cloudflare Pages hosts `frontend`; those accounts remain separately managed to avoid paying for redundant GCP services.

## Bootstrap order

1. Create a dedicated GCP project with billing and authenticate Terraform through Application Default Credentials or CI Workload Identity Federation.
2. Copy `terraform/terraform.tfvars.example` to an ignored `.tfvars` file and run `terraform init`. Bootstrap only the APIs, Artifact Registry, and empty secret containers with `terraform apply -target=google_project_service.required -target=google_artifact_registry_repository.backend -target=google_secret_manager_secret.runtime`.
3. Add the five core secret values with `gcloud secrets versions add SECRET_ID --data-file=...`; do not put values in Terraform variables or state. Add the three payOS versions only when enabling payOS. When enabling email, add `RESEND_API_KEY` and a random 32-byte base64 `NOTIFICATION_ENCRYPTION_KEY`; never reuse the database or auth secret. Terraform deliberately owns containers, not secret payloads.
4. Build `backend/Dockerfile`, push it to the new Artifact Registry repository, pin `backend_image` by digest, then run and review a complete `terraform plan` before the full `terraform apply`. Targeted apply is used only for the one-time bootstrap.
5. Configure Cloudflare Pages with root `frontend`, build command `npm ci && npm run build`, output `dist`, the two public Supabase variables from `frontend/.env.example`, and the server-only Pages Functions variable `BACKEND_ORIGIN=${api_url}`. The checked-in `/api/*` function keeps browser requests same-origin and rejects non-HTTPS upstreams.
6. Register exact Supabase OAuth redirect URLs and the payOS webhook URL `${api_url}/api/v1/webhooks/payos`. Keep `payos_enabled=false` until the merchant account, secrets, return URL, cancellation URL, and signed webhook test are all ready.
7. Verify the sending domain in Resend, set `notification_from`, then enable `notifications_enabled`. Send an invitation in staging and confirm delivery before enabling it in production. Notification payloads are encrypted at rest and redacted after delivery; keep the encryption key available until every pending job has completed.
8. Replace every `legal_policies` draft value with the exact counsel-reviewed document version and HTTPS URL. Set `reviewed=true` only after approval; a production Cloud Run revision otherwise fails startup by design. Any policy-content change requires a version bump and user re-consent.

Production deletion protection is on. Secret versions, DNS, Supabase, payOS merchant activation, billing budgets, and GitHub OIDC trust intentionally require account-owner decisions and are not fabricated by this repository.

Set `billing_account_id` before production so Terraform creates a project-scoped monthly budget with alerts at 50%, 80%, and 100%. The default USD 25 budget is a guardrail, not a spending cap; provider consoles still need hard quota/cap settings where supported.

The billing reconciliation schedule also processes due privacy deletions and retention cleanup, avoiding extra paid Scheduler jobs. Retention defaults redact raw Gemini output after 30 days and processed payment webhook bodies after 90 days while preserving canonical packages and webhook dedupe keys; review the `RETENTION_*` values against signed contracts before deployment. Application rate limits are deliberately per Cloud Run instance and memory-bounded; Cloudflare rate limiting is still required as the distributed IP/bot layer, while entitlement reservation remains the canonical protection against paid AI overuse.
