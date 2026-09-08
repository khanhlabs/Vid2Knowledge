# Vid2Knowledge — Kế hoạch triển khai chi tiết

## 1. Quyết định đã chốt

- Thị trường đầu tiên: Việt Nam.
- Buyer chính: đơn vị đào tạo/cohort academy nhỏ tại Việt Nam có 2–20 giảng viên, học viên trả phí và thư viện video hợp pháp; creator độc lập là phân khúc thứ hai.
- User: học viên của buyer; learner không phải người trả tiền chính ở giai đoạn đầu.
- Mô hình: B2B2C, land-and-expand từ paid pilot lên Creator, Training Team và Enterprise.
- Kiến trúc: modular monolith; không tách microservice khi chưa có bằng chứng tải hoặc ownership bắt buộc.
- Frontend: React 19 + TypeScript + Vite 8 trên Cloudflare Pages.
- Backend: Java 25 + Spring Boot 4.1 trên Google Cloud Run tại Singapore.
- Job nền: Google Cloud Tasks gọi một Cloud Run worker riêng; Cloud Scheduler phục hồi outbox và tác vụ định kỳ.
- Database/Auth: Supabase PostgreSQL + Supabase Auth tại Singapore.
- Object storage: Cloudflare R2 cho export và nguồn upload hợp pháp; không tải hoặc lưu video YouTube.
- Thanh toán Việt Nam: payOS/VietQR, webhook ký HMAC, subscription được mô hình hóa nội bộ; ưu tiên trả trước theo năm.
- AI: Gemini sau một `AiProvider` port, model/prompt/schema được version hóa; không phụ thuộc vào mức giá preview bằng 0.

Chi tiết kiến trúc nằm tại `Technical-Architecture.md`; data model, API và event contract nằm tại `Data-and-API.md`.

## 2. Nguyên tắc thực thi

1. Mỗi phase phải có migration, API contract, telemetry, test, runbook và rollback tương ứng.
2. Mọi bảng nghiệp vụ đa tenant có `organization_id`; mọi truy vấn và command phải qua authorization service.
3. Mọi side effect quan trọng phải idempotent: tạo job, dispatch task, Gemini call, webhook thanh toán, gửi email và cấp entitlement.
4. Không dùng frontend hoặc redirect URL làm nguồn sự thật cho payment, entitlement, quiz answer hay quota.
5. Không log token, nội dung riêng tư, prompt chứa dữ liệu người dùng hoặc payload thanh toán đầy đủ.
6. Tính năng chỉ được phát hành sau khi có metric, cost attribution và feature flag.
7. “Xây đầy đủ” là target scope; thứ tự vẫn dựa trên dependency, độ an toàn và khả năng tạo doanh thu.

## 3. Phase 0 — Paid demand và profit baseline

### 3.1 Buyer research

- Lập danh sách 100 đơn vị đào tạo/cohort academy tại Việt Nam đúng ICP; xác định founder/academic manager/operations manager là economic buyer.
- Thực hiện tối thiểu 20 discovery interview; ghi workflow hiện tại, giờ công soạn bài, số video/phút nguồn, số cohort/learner, completion, churn, công cụ thay thế, quyền nội dung, ngân sách và buying process.
- Tách dữ liệu primary training buyer khỏi creator secondary; không trộn kết quả hai segment.
- Lập competitor/substitute matrix và win/loss log; kiểm tra trực tiếp vì sao buyer không dùng ChatGPT, LMS hoặc nhân sự soạn bài.

### 3.2 Paid pilot sales

- Dùng chính video của prospect tạo demo có watermark; không xây dashboard riêng cho demo.
- Public sample chỉ là artifact minh họa zero-AI để buyer hiểu workflow trước signup; sample interaction
  không tính activation. First-touch allowlist được chụp khi tạo organization và báo cáo tới paid/net
  revenue; quyết định funnel dựa trên conversion thật, không dựa click.
- `/pilot` thu authority/volume/cohort/goal và consent bằng idempotent public API; sales queue dùng OIDC
  identity riêng, state trail bất biến và link `WON` tới organization để đo source/campaign → net cash.
  Lead chưa mua được redact PII sau 180 ngày; fit score chỉ ưu tiên founder time, không phải demand proof.
- Đề xuất pilot 5–15 triệu VNĐ với scope 300–600 phút, một cohort, human QA và outcome report.
- Proposal ghi success metric, content rights, dữ liệu xử lý, support boundary, thời hạn và điều kiện chuyển recurring plan.
- Thu tiền/cam kết mua trước khi coi pilot là valid; lời khen, signup và survey intent không thay thế payment evidence.

### 3.3 Profit model

- Tạo spreadsheet P&L theo account cho base/adverse/high-usage: revenue, payment, AI/shadow AI, infra, storage, email, founder onboarding/support time, refund, tax reserve và CAC.
- Gắn mọi entitlement với processed minutes, active learners, seats/cohorts và cost driver.
- Chốt provisional price/allowance từ `Business-Model.md`; ghi giả định và sensitivity khi provider cost tăng 2×, 5× và 10×.

### Gate

- Có ít nhất 3 paid pilot hoặc purchase commitment đủ tin cậy từ primary buyer.
- Ít nhất 2 buyer cung cấp cohort và source hợp pháp để chạy pilot.
- Adverse-case contribution margin có đường đạt ≥ 60% sau onboarding.
- Không đạt thì thay buyer/problem/offer/pricing; không dùng thêm code để che demand yếu.

## 4. Phase 1 — Chuẩn hóa nền móng

### 4.1 Repository và local development

- Chuyển frontend sang TypeScript strict; thêm ESLint, Prettier và import-boundary rules.
- Giữ monorepo hiện tại; chuẩn hóa `frontend`, `backend`, `infra`, `docs` và thêm ADR.
- Local dùng Docker Compose cho PostgreSQL; thêm Mailpit và LocalStack chỉ khi có test cần thiết. Cloud Tasks được giả lập bằng adapter synchronous/local queue, không cố mô phỏng toàn bộ GCP.
- Tạo `.env.example`; rotate mọi secret từng xuất hiện trong file `.env`; xác nhận `.env` bị ignore.
- Tạo Makefile hoặc PowerShell task wrapper cho `dev`, `test`, `lint`, `build`, `db-migrate`, `smoke`.

### 4.2 Backend baseline

- Dependencies: Spring MVC, Validation, Data JPA, Security Resource Server, OAuth2 Client nếu cần admin login, Actuator, Flyway, PostgreSQL driver, Resilience4j, springdoc-openapi, Micrometer/OpenTelemetry.
- Test: JUnit 5, AssertJ, Testcontainers PostgreSQL, WireMock, Awaitility, Spring Security Test, ArchUnit.
- Package theo bounded context: `identity`, `organization`, `catalog`, `analysis`, `learning`, `assignment`, `billing`, `usage`, `notification`, `analytics`, `audit`, `common`.
- Mỗi context có `api`, `application`, `domain`, `infrastructure`; cấm controller gọi repository trực tiếp.
- Chuẩn lỗi RFC 9457 Problem Details với `type`, `title`, `status`, `code`, `detail`, `correlationId`, `fieldErrors`.
- ID dùng UUIDv7; timestamp UTC `timestamptz`; amount dùng integer VND; không dùng floating point cho tiền.

### 4.3 Frontend baseline

- React Router cho routing; TanStack Query cho server state; React Hook Form + Zod cho form/validation; shadcn/ui + Radix primitives + Tailwind CSS cho accessible UI; i18next với tiếng Việt mặc định và namespace sẵn cho tiếng Anh.
- MSW cho API mocks; Vitest + Testing Library cho component; Playwright cho E2E.
- Không thêm Redux khi chưa có client-state phức tạp; auth, organization và feature flags đi qua providers nhỏ.
- Route groups: public/auth, learner, creator/admin, billing và internal support.

### 4.4 CI và supply-chain

- GitHub Actions: frontend lint/typecheck/unit/build; backend format/test/integration/package; migration validation; Playwright smoke; Docker build; Trivy image scan; dependency review.
- Dùng GitHub OIDC để deploy GCP; không lưu service-account JSON trong GitHub secrets.
- Dependabot/Renovate theo lịch; tạo SBOM CycloneDX cho backend và frontend production build.

### Definition of done

- Một lệnh chạy được local stack; CI xanh từ clean checkout.
- Testcontainers xác nhận Flyway chạy từ database trống.
- Không còn secret trong Git history hiện hành hoặc bundle.
- ADR-001 chốt modular monolith; ADR-002 chốt intentional multi-provider low-cost architecture.

## 5. Phase 2 — Feasibility, AI quality và cost engine

### 5.1 Provider abstraction

- Định nghĩa `AiProvider.analyze(SourceDescriptor, OutputProfile, GenerationContext)` và `AiProvider.answer(...)`.
- `GeminiAiProvider` là adapter đầu tiên; HTTP client có connect/read timeout, retry chỉ cho lỗi transient, exponential backoff + jitter và circuit breaker.
- Không retry lỗi validation, unsupported source, safety rejection hoặc quota cứng.
- Lưu `provider`, `model`, `modelVersion`, `promptVersion`, `schemaVersion`, usage tokens, latency, retry count và estimated/shadow cost.

### 5.2 Pipeline tạo học liệu

1. Validate và canonicalize URL/video ID.
2. Reserve quota theo organization trong transaction.
3. Tạo `analysis_job` và outbox event.
4. Cloud Tasks gọi worker bằng OIDC; worker claim job bằng optimistic locking.
5. Gemini tạo source map có section, factual claim và timestamp.
6. Từ source map sinh summary, flashcard và quiz; ưu tiên two-pass nếu benchmark chứng minh chất lượng tăng đủ so với chi phí.
7. Validate JSON Schema và domain rules: số option, index đáp án, duplicate, độ dài, timestamp range, empty content.
8. Cho phép tối đa một repair call; sau đó fail có mã lỗi, không loop vô hạn.
9. Persist immutable generation và version editable riêng.
10. Commit usage ledger; release phần quota không dùng; phát event hoàn thành.

### 5.3 Benchmark

- Ít nhất 50 video phân tầng theo tiếng Việt/Anh, độ dài, lecture/podcast/screen demo/slide, transcript availability và chất lượng âm thanh.
- So sánh static-low, static-high và agentic processing nếu model hỗ trợ.
- Đo schema success, factual accuracy, timestamp accuracy, serious-defect rate, p50/p95 latency, actual cost, shadow cost và repeat variance.
- 10–15 nội dung có pre-test, immediate post-test và delayed recall 3–7 ngày.
- Kết quả ghi vào dataset CSV/JSON có version; `feasibility-result.md` chỉ là report và decision record.

### Gate

- Valid package ≥ 95% trên nhóm video được hỗ trợ.
- Serious factual/timestamp defect ≤ 2% item được audit.
- Quiz không có đáp án sai/ambiguous ≥ 95% item.
- p95 job hoàn thành trong giới hạn công bố.
- Contribution margin dương trong adverse shadow-price model.
- Không đạt thì thu hẹp input/profile hoặc đổi pipeline trước khi phát triển UI lớn.

## 6. Phase 3 — Identity, organization và tenant security

### 6.1 Authentication

- Supabase Auth: Google OAuth cho creator; email magic link cho learner; password chỉ bật nếu có nhu cầu bắt buộc.
- Frontend giữ session theo SDK chính thức; backend verify JWT qua JWKS, kiểm tra issuer, audience, expiry và clock skew.
- User local được provision idempotently từ `sub`; không tin email/role gửi từ client.

### 6.2 Authorization

- Roles: `OWNER`, `ADMIN`, `INSTRUCTOR`, `REVIEWER`, `LEARNER`, `SUPPORT_READONLY`.
- Permission service kiểm tra membership + resource organization; controller dùng method authorization.
- Composite FK hoặc constraint đảm bảo child resource cùng tenant với parent.
- Support impersonation không triển khai; support access dùng explicit grant, read-only mặc định và audit bắt buộc.

### 6.3 Organization lifecycle

- Tạo organization, invitation, accept/revoke, đổi role, ownership transfer, deactivate member.
- Một user có thể thuộc nhiều organization; active organization nằm trong URL/context, không ghi cố định vào JWT.
- Tenant-isolation integration tests cho mọi repository/API; test negative path là bắt buộc.

### Gate

- Không thể đọc/ghi resource chéo tenant qua ID enumeration.
- Invitation dùng token hash, expiry, single-use và revoke được.
- Audit log ghi actor, organization, action, resource, timestamp, correlation ID.

## 7. Phase 4 — Creator authoring và content lifecycle

### 7.1 Catalog

- Course → Module → Lesson → Source → Learning Package Version.
- Import một URL hoặc playlist public; playlist metadata chỉ dùng API/path tuân thủ YouTube policy.
- Rights attestation bắt buộc trước processing; lưu version điều khoản được đồng ý.

### 7.2 Authoring studio

- TipTap editor cho overview/section; card/quiz có structured editor, reorder, duplicate, delete và preview.
- Draft → generated → in_review → approved → published → archived.
- Optimistic concurrency bằng `version`; conflict UI không silently overwrite.
- Generation immutable; edit tạo content revision và audit diff.
- Reusable output templates theo audience, language, difficulty, counts và brand voice.
- Question bank có tags, difficulty, source reference, validation status và usage history.

### 7.3 Quality workflow

- Auto rules: duplicate similarity, empty distractor, answer-index mismatch, timestamp out of range, prohibited content và excessive length.
- Reviewer có queue; approve/reject với reason; package chưa approved không được publish nếu organization bật approval-required.
- Learner report tạo quality issue liên kết exact revision.

### Gate

- Creator hoàn thành URL → approved package → publish mà không cần database/manual fix.
- Autosave không mất dữ liệu; concurrent edit tạo conflict rõ ràng.
- Mọi learner-visible item truy ngược được source và revision.

## 8. Phase 5 — Learner delivery và retention

### 8.1 Assignment

- Cohort, membership, assignment, deadline, availability window, completion rule và attempt policy.
- Invitation bằng magic link; buyer chọn yêu cầu account hoặc access token giới hạn.
- Progress events idempotent và server-timestamped.

### 8.2 Learning experience

- YouTube IFrame Player API đồng bộ timestamp; notes và claim mở đúng thời điểm.
- Quiz grading ở server; frontend chỉ hiển thị kết quả được server trả.
- Flashcard dùng FSRS-compatible scheduler; lưu review rating, stability/difficulty, due date và history.
- Exam mode tạo assessment từ approved question bank, randomize option an toàn, attempt snapshot và delayed-recall assignment.
- Learning path hỗ trợ prerequisite, progress và completion certificate nội bộ; không tuyên bố chứng chỉ được công nhận.

### 8.3 Ask Video/Course

- RAG index từ approved source map, notes và buyer-provided transcript; dùng PostgreSQL `pgvector`.
- Chunk có source lesson, timestamp/page và revision; embedding được version hóa.
- Answer phải trả citation; thiếu evidence thì từ chối hoặc đề nghị mở nguồn.
- Premium fallback gọi lại video provider chỉ khi entitlement cho phép; có per-user/day budget.
- Prompt-injection filtering cho source content; không cho source override system policy hoặc exfiltrate data.

### 8.4 Notifications

- Resend cho invitation, assignment, reminder, payment/dunning; queue mọi email và dedupe bằng notification key.
- User preference + unsubscribe cho email không bắt buộc; transactional và marketing tách biệt.

### Gate

- Mobile responsive đạt WCAG 2.2 AA cho critical flow.
- Resume đúng trạng thái giữa thiết bị.
- Không lộ correct answer trước submit.
- Retention và learning-gain events đối soát được với database.

## 9. Phase 6 — Analytics và buyer ROI

### 9.1 Canonical events

- Business events ghi transactionally vào PostgreSQL/outbox; PostHog dùng cho product exploration, không phải nguồn sự thật cho billing.
- Event schema versioned; không gửi raw notes, quiz answer, email hoặc token sang analytics.
- Các funnel: invite → open → start → quiz complete → delayed review → completion.

### 9.2 Dashboards

- Creator: course/cohort activation, completion, attempt, score distribution, weak topics, delayed recall, feedback và content defects.
- Commercial: qualified lead, pilot, recurring conversion, GRR, NRR, expansion/contraction MRR, churn, CAC, payback và margin.
- Cost: provider/model/profile, video-minute, retry, repair, Ask usage, cache hit và organization cost.
- Metric definitions cố định trong metric registry; dashboard ghi timezone và denominator.

### Gate

- Tổng hợp dashboard đối soát với raw DB sample.
- Buyer xuất được CSV/PDF report permissioned.
- Không mô tả metric nội bộ là YouTube metric hoặc trộn nguồn mà không gắn nhãn.

## 10. Phase 7 — Billing và entitlement

### 10.1 Payment architecture

- `PaymentProvider` port; `PayOsPaymentProvider` adapter đầu tiên.
- Tạo payment order/link ở backend; `orderCode` unique; amount lấy từ price catalog server-side.
- Webhook verify HMAC trên raw payload, store inbox event trước xử lý, idempotent theo provider event/reference.
- Redirect success chỉ là UX; chỉ webhook verified hoặc reconciliation API được cấp entitlement.
- Renewal monthly qua invoice/payment link và dunning; ưu tiên annual prepay để cải thiện cash flow. Không giả định payOS hỗ trợ recurring card debit.

### 10.2 Billing domain

- Product, Price, PlanVersion, Subscription, Invoice, InvoiceLine, Payment, Refund, CreditGrant, UsageLedger, Entitlement và BillingAdjustment.
- Plan/price immutable sau khi active; đổi giá tạo version mới.
- Entitlement có effective window; usage reservation/commit/release atomic.
- Billing period theo timezone contract nhưng lưu UTC; invoice number/order code không tái sử dụng.
- Đổi plan self-serve có hiệu lực cuối kỳ: lưu `next_plan_id`, cho hủy trước khi renewal bắt đầu, tạo
  payment link trong cửa sổ 7 ngày và chỉ tạo entitlement kỳ mới sau webhook. Không dùng proration
  tức thời ở giai đoạn solo-operation; nhu cầu tăng capacity ngay dùng top-up có ledger/refund rõ ràng.
- Reconciliation job so sánh pending payment với payOS và cảnh báo mismatch.

### 10.3 Packaging

- Pilot: fixed scope, manual contract.
- Creator: base platform + included processed minutes/cohort; top-up credits.
- Training Team: seats/courses/cohorts + higher allowance + roles/analytics.
- Enterprise: annual minimum, SSO, audit, retention, integration, SLA và priority support.

### Gate

- Webhook duplicate/out-of-order không cấp trùng credit.
- Không thể sửa amount/plan từ client.
- Refund/cancel/dunning/expiry có integration tests và audit.
- Revenue, entitlement và cash receipt reconciliation khớp.

## 11. Phase 8 — Production infrastructure

### 11.1 Environments

- Local: Docker PostgreSQL, fake auth/payment/provider.
- Development: Cloud Run dev + Supabase free Singapore; không chứa dữ liệu khách thật.
- Production: Cloud Run production + Supabase Pro Singapore trước khi nhận khách trả tiền; có daily backup và không pause.
- PR dùng Testcontainers, MSW và ephemeral build; không tạo database cloud cho mỗi PR.

### 11.2 Deploy topology

- Cloudflare Pages: static frontend, custom domain, CSP/security headers và preview deploy.
- Cloud Run `api`: request-based billing, min instances 0 lúc đầu, 1 vCPU/1 GiB, concurrency khởi điểm 20, max instances 3, timeout 60s.
- Cloud Run `worker`: min 0, concurrency 1–2, max instances theo DB/provider quota, timeout phù hợp job nhưng task có lease/idempotency.
- Cloud Tasks: queue theo workload `analysis`, `notification`, `webhook-retry`; payload chỉ chứa ID, không chứa content lớn.
- Cloud Scheduler: outbox recovery, reconciliation, due-review dispatch và cleanup; gom tác vụ để nằm trong số job free khi có thể.
- Supabase Singapore: PostgreSQL, Auth; dùng Supavisor pooled connection và giới hạn Hikari pool theo tổng Cloud Run max instances.
- Cloudflare R2: private bucket, presigned URL, malware/type/size validation, lifecycle deletion.
- GCP Secret Manager: Gemini, payOS, Resend, Supabase service secrets; workload service account least privilege.

### 11.3 Observability

- Structured JSON logs với trace/correlation/job/org ID đã pseudonymize.
- OpenTelemetry traces, Micrometer metrics và Cloud Monitoring alerts.
- Sentry frontend/backend cho error grouping và release tracking; scrub PII.
- Alerts: error rate, task oldest age, job p95, DB connection saturation, payment webhook failure, budget anomaly và provider circuit open.

### 11.4 Backup, recovery và SLO

- Supabase Pro daily backup; định kỳ logical export mã hóa sang private object storage; quarterly restore test.
- RPO ban đầu 24h, RTO 4h; nâng theo hợp đồng.
- SLO: API availability 99.5% ban đầu, verified payment processing 99.9%, accepted job completion theo supported profile ≥ 95%.
- Runbook: provider outage, DB outage, task backlog, secret leak, payment mismatch, data deletion và rollback.

## 12. Phase 9 — Security, privacy và compliance Việt Nam

- Threat model theo boundary auth, tenant, upload, AI prompt, payment webhook và support tooling.
- OWASP ASVS L2 checklist cho critical controls; dependency/image scanning và annual penetration test trước enterprise.
- CSP, HSTS, secure cookies, CSRF cho cookie flow, CORS allowlist, rate limit account/IP/org và bot protection trên signup/generation.
- Encryption in transit/at rest; field-level encryption cho secret-like integration credentials.
- Data inventory, purpose, lawful basis/consent, retention schedule, export/delete workflow và processor register.
- Đánh giá chuyển dữ liệu ra nước ngoài và nghĩa vụ theo Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15; thuê tư vấn pháp lý trước public production/enterprise contract.
- Terms, Privacy, Acceptable Use, AI limitation, content-rights attestation, takedown/complaint và subprocessor list.
- Policy manifest và immutable acceptance ledger theo exact version; production fail startup nếu owner chưa
  xác nhận legal review/HTTPS URLs, và business API trả 428 cho user chưa accept current policy set.
- Không đưa trẻ em thành target ban đầu; nếu buyer phục vụ người chưa thành niên phải mở compliance project riêng trước khi enable.

## 13. Phase 10 — Enterprise và integrations

- API keys hash-at-rest, scoped permission, expiry/rotation, rate limit và audit.
- Signed webhooks với retry, delivery log, replay protection và secret rotation.
- LMS integration ưu tiên theo hợp đồng: LTI 1.3 trước nếu buyer cần; không tích hợp hàng loạt theo suy đoán.
- SSO SAML/OIDC, SCIM chỉ khi enterprise contract bù chi phí vận hành.
- Custom domain/branding, data retention control, legal hold/export, SLA report và support priority.
- Mọi custom feature phải có reusable product path, margin model và support boundary.

## 14. Phase 11 — Profit và scale optimisation

- Model routing theo output profile và quality tier; batch cho non-interactive; cache prompt/context khi có lợi.
- Cost ledger đến organization/job/feature; account P&L dùng reconciled revenue/refund và shadow AI cost.
- OWNER/ADMIN phải xác nhận tỷ giá, payment fee, infra, support, tax, CAC và churn trước khi
  dashboard được phép báo healthy; theo dõi gross/contribution margin, AI/revenue, payback và LTV/CAC.
- Direct-cost ledger chỉ nhận chi phí ngoại lệ ngoài monthly allocation, có tenant scope và audit log.
- Pricing review hàng quý: allowance, overage, annual discount, service cost và provider price.
- Billing profile Việt Nam dùng optimistic version/audit; invoice chụp immutable buyer identity và có
  safe CSV accounting handoff. Internal payment record không được gọi là hóa đơn điện tử; provider
  issuance, seller identity, tax calculation và accountant reconciliation là launch gate riêng.
- Promotion/referral attribution dùng immutable campaign và price snapshot, transactional capacity,
  one-redemption-per-organization và channel ceiling 15/20/25/30%. Chỉ scale campaign khi attributed
  revenue trừ discount, CAC và support vẫn đạt contribution-margin/payback gate; không dùng redemption
  count làm proxy cho lợi nhuận.
- Tự động onboarding/support; knowledge base và in-app diagnostics giảm ticket.
- Support diagnostics chỉ qua tối đa ba explicit OWNER grant đồng thời, mỗi grant 15 phút–24 giờ,
  internal OIDC, aggregate read-only/no-store response và immutable access event. Không triển khai
  impersonation hay blanket support role.
- Scale từng acquisition channel khi contribution LTV/CAC ≥ 3 và CAC payback ≤ 12 tháng.
- Theo dõi revenue concentration; không để một account tạo phần lớn doanh thu mà không có contract/SLA tương ứng.
- Provider contingency drill và migration test định kỳ.

## 15. Thứ tự milestone có thể giao

| Milestone | Kết quả bàn giao | Điều kiện chuyển bước |
|---|---|---|
| M-1 | 3 paid pilot + P&L baseline | Demand/profit gate đạt |
| M0 | Local/CI/ADR/security baseline | Build và test lặp lại được |
| M1 | Benchmark + cost engine | Quality/cost gate đạt |
| M2 | Auth + tenant + organization | Tenant isolation đạt |
| M3 | Creator URL-to-approved-package | Authoring E2E đạt |
| M4 | Assignment + learner + retention | Learning E2E đạt |
| M5 | Buyer ROI analytics | Metric reconciliation đạt |
| M6 | payOS + entitlement + revenue ledger | Payment audit/reconciliation đạt |
| M7 | Production launch controls | SLO/backup/runbook/legal readiness đạt |
| M8 | Full learning paths/Q&A/enterprise | Contract-driven acceptance đạt |
| M9 | Margin optimisation và channel scale | Commercial gates đạt |

## 16. Quy tắc bắt đầu code

Trước mỗi milestone phải có: user stories, acceptance criteria, API/OpenAPI diff, migration plan, threat-model delta, telemetry, test matrix, rollout flag và rollback. Nếu một quyết định liên quan pricing, quyền nội dung, dữ liệu cá nhân, SLA hoặc contract chưa rõ, dừng và hỏi product owner trước khi code.
