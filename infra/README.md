# Production infrastructure

The Terraform stack provisions the low-idle-cost GCP half of Vid2Knowledge: two scale-to-zero Cloud Run services, a rate-limited Cloud Tasks queue, an OIDC-authenticated outbox wake-up, Artifact Registry, least-privilege service accounts, and Secret Manager containers. Supabase hosts PostgreSQL/Auth and Cloudflare Pages hosts `frontend`; those accounts remain separately managed to avoid paying for redundant GCP services.

## Bootstrap order

1. Create a dedicated GCP project with billing and authenticate Terraform through Application Default Credentials or CI Workload Identity Federation.
2. Copy `terraform/terraform.tfvars.example` to an ignored `.tfvars` file and run `terraform init`. Bootstrap only the APIs, Artifact Registry, and empty secret containers with `terraform apply -target=google_project_service.required -target=google_artifact_registry_repository.backend -target=google_secret_manager_secret.runtime`.
3. Add the five core secret values with `gcloud secrets versions add SECRET_ID --data-file=...`; do not put values in Terraform variables or state. Add the three payOS versions only when enabling payOS. Terraform deliberately owns containers, not secret payloads.
4. Build `backend/Dockerfile`, push it to the new Artifact Registry repository, pin `backend_image` by digest, then run and review a complete `terraform plan` before the full `terraform apply`. Targeted apply is used only for the one-time bootstrap.
5. Configure Cloudflare Pages with root `frontend`, build command `npm ci && npm run build`, output `dist`, the two public Supabase variables from `frontend/.env.example`, and the server-only Pages Functions variable `BACKEND_ORIGIN=${api_url}`. The checked-in `/api/*` function keeps browser requests same-origin and rejects non-HTTPS upstreams.
6. Register exact Supabase OAuth redirect URLs and the payOS webhook URL `${api_url}/api/v1/webhooks/payos`. Keep `payos_enabled=false` until the merchant account, secrets, return URL, cancellation URL, and signed webhook test are all ready.

Production deletion protection is on. Secret versions, DNS, Supabase, payOS merchant activation, billing budgets, and GitHub OIDC trust intentionally require account-owner decisions and are not fabricated by this repository.
