# Vid2Knowledge — Kiến trúc kỹ thuật mục tiêu

## 1. Kiến trúc tổng thể

```text
Browser/PWA
  └─ Cloudflare Pages (React)
       ├─ Supabase Auth (Google OAuth / magic link)
       └─ Cloud Run API (JWT)
            ├─ Supabase PostgreSQL Singapore
            ├─ Transactional Outbox → Cloud Tasks → Cloud Run Worker
            ├─ Gemini through AiProvider
            ├─ payOS through PaymentProvider
            ├─ Resend through NotificationProvider
            ├─ Cloudflare R2 through ObjectStorage port
            └─ Structured events/logs → Cloud Monitoring native
```

Đây là multi-provider có chủ đích để giữ chi phí thấp: Cloudflare cho static/egress, GCP cho compute/queue gần Gemini, Supabase cho Postgres/Auth. Mọi integration phải nằm sau port để có thể thay thế. Không tạo distributed transaction giữa provider; dùng inbox/outbox, idempotency và reconciliation.

## 2. Lý do chọn công nghệ

| Phần | Chọn | Lý do | Khi nâng cấp |
|---|---|---|---|
| Web | React 19, TypeScript, Vite 8 | Khớp source, build static rẻ | Không đổi framework nếu không có bottleneck |
| UI | Tailwind, shadcn/ui, Radix | Nhanh nhưng vẫn accessible và tùy biến | Design system riêng khi brand lớn |
| API | Java 25, Spring Boot 4.1 | Khớp source, type-safe, mature security/data | Giữ modular monolith càng lâu càng tốt |
| DB/vector | Supabase PostgreSQL Singapore + pgvector | Free cho dev; Pro có backup và latency gần VN; local/test pin `pgvector/pgvector:0.8.6-pg18-bookworm` | Chuyển managed Postgres khác qua Flyway/standard SQL khi economics yêu cầu |
| Auth | Supabase Auth | Google OAuth, magic link, invitation; giảm security code | Enterprise SSO qua adapter/IdP khi có hợp đồng |
| Compute | Cloud Run Singapore | Scale-to-zero, container chuẩn, request-based | Min instance > 0 khi latency/revenue biện minh |
| Queue | Cloud Tasks | Durable push, retry/rate limit, 1M ops free/tháng | Pub/Sub chỉ khi fan-out/event volume thực sự cần |
| Scheduler | Cloud Scheduler | Outbox/reconciliation/notification, 3 jobs; cleanup và learning reminders được gộp vào hai cycle sau | Dedicated scheduler khi task set vượt giới hạn vận hành |
| Storage | Cloudflare R2 | S3-compatible, free egress, lifecycle | Tách bucket/region hoặc enterprise storage theo compliance |
| Payment | payOS/VietQR | Phù hợp Việt Nam, webhook và tiền về tài khoản | Thêm adapter quốc tế; Stripe không phải mặc định cho pháp nhân VN |
| Email | Resend | API đơn giản, free 3.000 email/tháng | Dedicated provider/IP khi deliverability/volume yêu cầu |
| Product analytics | PostgreSQL business events + reconciled metrics | Không gửi PII sang vendor, cùng nguồn sự thật với revenue | PostHog chỉ sau consent/cost review và nhu cầu experiment thật |
| Error/trace | Structured JSON + Cloud Monitoring native trước; Sentry/OTel khi SLO cần | Không trả APM khi chưa có traffic, vẫn có correlation và alert | Chỉ bật vendor sau cost review, scrub PII |

Tham chiếu giá phải revalidate trước deploy: [Cloud Run](https://cloud.google.com/run/pricing), [Cloud Tasks](https://cloud.google.com/tasks/pricing), [Cloud Scheduler](https://cloud.google.com/scheduler/pricing), [Supabase](https://supabase.com/pricing), [Cloudflare Pages](https://developers.cloudflare.com/pages/platform/limits/), [R2](https://developers.cloudflare.com/r2/pricing/), [Resend](https://resend.com/pricing), [PostHog](https://posthog.com/pricing), [payOS](https://payos.vn/docs/api/).

## 3. Backend module boundaries

- `identity`: local user projection, profile, auth claims mapping.
- `organization`: tenants, memberships, invitations, roles.
- `catalog`: course, module, lesson, source, template, revisions.
- `analysis`: jobs, provider, prompt/schema, source map, quality validation.
- `learning`: flashcard scheduling, assessments, attempts, mastery, Q&A.
- `assignment`: cohort, learner membership, assignment, progress, deadline.
- `billing`: product/price/subscription/invoice/payment/refund/entitlement.
- `usage`: reservation, commit, release, cost ledger và limit enforcement.
- `notification`: transactional email, preferences, delivery state.
- `analytics`: canonical business events và aggregate projections.
- `audit`: immutable security/business audit trail.

Module giao tiếp bằng application ports và domain events trong cùng process. Event cần side effect ngoài process được ghi vào outbox trong cùng DB transaction. Không chia database theo module ở giai đoạn này.

## 4. Consistency và idempotency

- HTTP mutation nhận `Idempotency-Key`; lưu hash actor + endpoint + request fingerprint + response reference.
- Payment webhook dùng provider reference; task dùng deterministic task name; notification dùng business notification key.
- Job state transition dùng compare-and-set `version`; worker chỉ xử lý state hợp lệ.
- Usage: `RESERVED → COMMITTED/RELEASED`; ledger append-only, balance là projection có lock.
- Outbox dispatcher dùng `FOR UPDATE SKIP LOCKED`; inbox dedupe bằng unique provider/event key.
- Không gọi external provider bên trong database transaction.

## 5. AI architecture

### Contracts

- `SourceDescriptor`: type, canonical URI/object key, language hint, rights record.
- `OutputProfile`: use case, language, depth, media resolution, counts, quality tier.
- `GenerationContext`: organization, job, prompt/schema version, budget ceiling.
- `GenerationResult`: source map, package draft, usage, safety, quality signals.

### Safety và quality

- Structured output JSON schema; domain validator độc lập.
- Source map tách khỏi presentation output để giảm hallucination lan truyền.
- `verificationStatus` chỉ do validator/reviewer đặt; model không tự chứng nhận.
- Prompt/template immutable theo version; rollout qua feature flag/cohort.
- Red-team prompt injection, malicious transcript, unsupported language và oversized input.
- Output profile được normalize và whitelist trước khi reserve quota; worker dùng đúng profile đã
  fingerprint trong prompt. Không chèn brand voice/free text trực tiếp vào instruction.
- Cache key gồm normalized source, rights scope, content fingerprint, output profile, model/prompt/schema; không share cross-tenant nếu chưa có quyền rõ ràng.

## 6. Security architecture

- Internet chỉ tới Pages, public API và payment webhook; worker/internal endpoints yêu cầu GCP OIDC.
  Cloud Tasks/Scheduler và sales operator dùng hai service account + audience khác nhau; task identity
  không đọc được lead PII và sales identity không gọi được task handler.
- JWT xác thực identity; organization membership trong DB xác thực authorization.
- Service-role key không xuất hiện trong frontend. Frontend chỉ dùng public Supabase key theo thiết kế.
- R2 object private, presigned URL thời hạn ngắn, key prefix tenant-randomized.
- Rate limit nhiều tầng: Cloudflare/WAF cho bot; application cho account/IP/org/endpoint; Cloud Tasks cho provider throughput.
- Application limiter áp fixed window riêng cho mutation thường, AI-expensive và payment,
  đồng thời giới hạn số key trong memory. Đây là guardrail per-instance; quota transaction trong
  PostgreSQL mới là nguồn sự thật chống vượt chi phí, còn Cloudflare là distributed outer layer.
- Audit không lưu secret hoặc raw sensitive body; log có retention và access policy.
- Secrets rotate được; payOS webhook signature verify constant-time; replay event không tạo side effect.

## 7. Capacity và cost controls

- API max instances × Hikari max pool không vượt DB connection budget; dành connection cho migration/admin.
- Worker concurrency ban đầu 1–2 để hạn chế Gemini burst và DB contention.
- Mỗi job có max provider calls, max output tokens, deadline và VND shadow budget.
- Organization có daily/monthly hard cap; global provider kill switch và per-feature flag.
- Payload task < 32 KB; content nằm DB/object storage.
- Metrics cost theo provider/model/profile/org/job; alert margin âm hoặc usage anomaly.

## 8. Deployment và rollback

- Build immutable image theo commit SHA; Artifact Registry; deploy revision mới không overwrite.
- Database migration dùng expand/contract: thêm schema tương thích trước, deploy app, backfill idempotent, sau đó cleanup ở release sau.
- Cloud Run traffic splitting/canary cho release rủi ro; rollback app chỉ tới version tương thích schema.
- Frontend build gắn API contract version; giữ previous Pages deployment để rollback.
- Feature flag mặc định off cho AI model, billing rule và enterprise feature mới.

## 9. Chi phí khởi đầu dự kiến

- Cloudflare Pages: có thể $0 trong giới hạn free.
- Cloud Run/Tasks/Scheduler: có thể gần $0 ở tải nhỏ nhờ free tier, nhưng phải bật billing và budget alerts.
- Supabase dev: $0; production trả tiền trước khách thật để có backup và tránh pause.
- R2, Resend, PostHog, Sentry: bắt đầu trong free tier, đặt cap/alert.
- Gemini: tính actual cost và shadow cost; không coi preview-free là doanh thu biên.
- Domain, pháp lý, email deliverability và database production là chi phí không nên né nếu ảnh hưởng niềm tin/doanh thu.

Không có cam kết “miễn phí hoàn toàn”: mục tiêu là fixed cost thấp và variable cost gắn với doanh thu.
