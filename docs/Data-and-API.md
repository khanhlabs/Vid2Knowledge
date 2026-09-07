# Vid2Knowledge — Data model, API và event contract

## 1. Quy ước dữ liệu

- Primary key UUIDv7; external slug tách khỏi ID.
- `created_at`, `updated_at` là UTC `timestamptz`; soft delete chỉ nơi cần audit/restore.
- Mọi tenant table có `organization_id NOT NULL` và index bắt đầu bằng `organization_id` cho access path phổ biến.
- JSONB dùng cho immutable AI payload/config/snapshot; quan hệ cần query, permission, billing hoặc constraint phải normalize.
- Money là `amount_vnd BIGINT`; percentage/rate dùng numeric có scale rõ.
- PII có data classification và retention; email normalized nhưng hiển thị giữ bản gốc khi cần.

## 2. Nhóm bảng cốt lõi

### Identity và tenant

- `users(id, auth_subject, email, display_name, locale, status, last_login_at)`.
- `organizations(id, name, slug, status, timezone, settings_json)`.
- `memberships(organization_id, user_id, role, status, joined_at)`; unique org/user.
- `invitations(id, organization_id, email, role, token_hash, expires_at, accepted_at, revoked_at)`.
- `audit_logs(id, organization_id, actor_user_id, action, resource_type, resource_id, metadata_json, correlation_id, created_at)`; append-only.

### Catalog và content

- `courses`, `course_modules`, `lessons`; có position và publication state.
- `sources(id, organization_id, type, canonical_uri, external_id, content_hash, rights_attestation_id, metadata_json)`.
- `source_uploads` giữ reservation, declared metadata, processing lease/attempt, rights basis và resulting
  `source_id`; object key bền vững chỉ nằm trong server-side source metadata.
- `rights_attestations(id, organization_id, source_id, attested_by, basis, terms_version, attested_at)`.
- `analysis_jobs(id, organization_id, source_id, profile_id, state, attempt, idempotency_key, provider_config_id, reserved_usage_id, error_code, version, timestamps...)`.
- `generation_runs(id, job_id, provider, model, prompt_version, schema_version, request_fingerprint, usage_json, actual_cost, shadow_cost, latency_ms, output_json, created_at)`; immutable.
- `learning_packages(id, organization_id, lesson_id, current_revision_id, publication_state)`.
- `package_revisions(id, package_id, revision_no, based_on_generation_id, content_json, edited_by, verification_state, created_at)`; immutable revision.
- `content_templates` lưu output profile đã whitelist/version hóa theo tenant;
  `question_bank_items` chỉ nhận câu hỏi từ revision human-verified;
  `review_decisions` lưu reviewer, exact revision, quyết định và lý do từ chối bắt buộc.

### Learning và assignment

- `cohorts`, `cohort_memberships`, `assignments`, `assignment_targets`.
- `learner_progress(organization_id, user_id, assignment_id, state, started_at, completed_at, progress_percent, version)`.
- `assessment_snapshots`, `assessment_attempts`, `assessment_attempt_answers`; snapshot giữ nguyên revision, câu hỏi và thứ tự option tại thời điểm làm. Answer key chỉ ở server; mỗi lượt thi có cửa sổ 2 giờ, khóa idempotency và tenant FK.
- `flashcard_memory_states`, `flashcard_review_log`; review append-only, state là projection theo FSRS-6, lưu algorithm version để reschedule/migrate có kiểm soát.
- `lesson_prerequisites`, `course_completion_rules`, `completion_certificates`; prerequisite cùng course và chống cycle, certificate snapshot tiêu chí cấp, có verification code và trạng thái thu hồi.
- `mastery_states(organization_id, user_id, topic_key, score, evidence_count, updated_at)`.
- `qa_threads`, `qa_messages`, `qa_citations`, `qa_query_runs`, `embedding_chunks` với pgvector 768 chiều và revision reference. HNSW cosine index chỉ chứa chunk từ human-verified published revision; model embedding là một phần của unique key để re-index an toàn.
- `learner_feedback`, `content_reports`.

### Billing và operations

- `products`, `plan_versions`, `prices`, `subscriptions`, `subscription_items`.
- `invoices`, `invoice_lines`, `payments`, `refunds`, `billing_adjustments`.
- `entitlements`, `usage_reservations`, `usage_ledger`, `cost_ledger`; ledger append-only.
- `payment_webhook_inbox`, `outbox_events`, `idempotency_records`.
- `notification_jobs`, `notification_deliveries`, `notification_preferences`,
  `notification_preference_changes`. Preference mặc định bật hướng dẫn sản phẩm/nhắc bài tập nhưng tắt
  marketing; mỗi thay đổi có ledger bất biến để chứng minh consent. Job bị suppression chuyển
  `CANCELLED`, ghi lý do/thời điểm và redact payload mã hóa.
- `business_events`, `daily_organization_metrics`, `daily_course_metrics`.

Chi tiết column/constraint của từng bảng phải được ghi trong migration design trước milestone liên quan. Không tạo toàn bộ bảng ở migration đầu tiên.

## 3. State machines

### Analysis job

```text
QUEUED → PROCESSING → VALIDATING → COMPLETED
   └───────────────→ RETRY_SCHEDULED → PROCESSING
   └───────────────→ FAILED
QUEUED/RETRY_SCHEDULED → CANCELLED
```

Terminal state không quay lại; retry tạo attempt/run mới. Job completed luôn có valid package revision và committed usage.

### Package

```text
DRAFT → GENERATED → IN_REVIEW → APPROVED → PUBLISHED → ARCHIVED
                    └────────→ REJECTED → DRAFT
```

### Usage

```text
RESERVED → COMMITTED
         → RELEASED
         → EXPIRED
```

### Payment

```text
PENDING → PAID → PARTIALLY_REFUNDED → REFUNDED
       ↘ EXPIRED/CANCELLED/FAILED
```

## 4. Public API v1

### Identity/organization

- `GET /api/v1/me`
- `GET|PATCH /api/v1/me/notification-preferences`; PATCH yêu cầu gửi đủ ba lựa chọn
  `productGuidanceEnabled`, `assignmentRemindersEnabled`, `marketingEnabled`, không suy diễn field thiếu
  thành opt-out/opt-in. Marketing luôn opt-in, độc lập với email giao dịch bắt buộc.
- `GET/POST /api/v1/organizations`
- `GET/PATCH /api/v1/organizations/{orgId}`
- `GET/POST /api/v1/organizations/{orgId}/members`
- `PATCH/DELETE /api/v1/organizations/{orgId}/members/{userId}`
- `POST /api/v1/organizations/{orgId}/invitations`
- `POST /api/v1/invitations/{token}/accept`

### Catalog/authoring

- CRUD `/organizations/{orgId}/courses`, modules và lessons.
- Private source upload (OWNER/ADMIN/INSTRUCTOR; chỉ Training Team/Business đang active):
  - `POST .../source-uploads` reserve một object 1 KB–500 MiB cho `video/mp4|video/webm`, trả
    presigned PUT 15 phút và exact required headers. Filename không đi vào object key.
  - Browser PUT thẳng vào private R2; `POST .../source-uploads/{uploadId}/complete` HEAD lại exact
    byte count/content type và kiểm tra magic bytes trước khi đổi sang `STORAGE_VERIFIED`.
  - `GET .../source-uploads` tenant-scoped. Completion idempotent; mismatch bị `REJECTED` và xóa object.
    Tối đa 10 reservation pending/tổ chức để chặn storage abuse.
  - `POST .../source-uploads/{uploadId}/ingest` chốt rights basis/language và queue ingestion. Worker stream
    R2 → Gemini Files API, lấy duration provider-verified, copy-then-commit sang durable tenant key và trả
    `sourceId` khi `READY`. File Gemini được reuse cho analysis kế tiếp rồi xóa; nếu hết TTL thì upload lại.
- `GET .../sources/{sourceId}/playback` trả presigned GET 15 phút. Staff cùng tenant được xem; LEARNER chỉ
  được ký URL khi có assignment published, available và trỏ tới đúng source.
- `POST .../sources` validate source và rights attestation.
- `POST .../analyses` với `Idempotency-Key`; trả `202` + job URI.
- `GET/POST .../analysis-jobs/{jobId}` cho status/cancel/retry hợp lệ.
- `GET .../packages/{packageId}` trả ETag; `PATCH .../packages/{packageId}/draft` dùng If-Match.
  UI chỉnh structured summary/section/takeaway/flashcard/quiz, reorder/duplicate trong giới hạn schema
  và giữ bản chưa lưu trong local storage theo đúng package + base version. Mỗi lần lưu tạo revision
  bất biến; HTTP 412 giữ lại local draft để người dùng đối chiếu thay vì silently overwrite.
- `POST .../packages/{packageId}/submit-review|approve|reject|publish|archive`; reject bắt buộc
  reason 3–1000 ký tự. Khi `approvalRequired=false`, author publish là human verification;
  mặc định tổ chức mới vẫn bắt buộc reviewer approval.
- CRUD `/authoring/templates` dùng ETag/If-Match; request analysis chọn chính xác một trong
  `templateId` hoặc inline `outputProfile`. Profile chỉ nhận enum đã whitelist và 10–20
  flashcard/5–10 quiz để ngăn prompt injection và cost expansion.
- `GET /authoring/review-queue|question-bank|settings`; resolution phản hồi luôn có người xử lý,
  lý do và audit log.

### Cohort/learner

- CRUD cohorts, learner invitations và assignments.
- `GET /api/v1/learner/assignments`
- `GET /api/v1/learner/assignments/{id}`
- `POST .../start`, `POST .../progress`, `POST .../complete` idempotent.
- `GET .../assignments/{assignmentId}/assessments/overview` trả số lượt, delayed-recall eligibility và top điểm yếu.
- `POST .../assignments/{assignmentId}/assessments` tạo snapshot `PRACTICE|DELAYED_RECALL`; `POST .../assessments/{snapshotId}/submit` chấm snapshot phía server. Cả hai bắt buộc `Idempotency-Key`; delayed recall mở sau lượt practice đầu tiên 3 ngày.
- `GET .../learner/reviews/due|summary`, `POST .../assignments/{assignmentId}/flashcards/{cardId}/reviews`; rating bắt buộc idempotent và tenant-scoped.
- `POST .../packages/{packageId}/knowledge-index` tạo/cập nhật vector index bằng Gemini embedding batch; author chủ động bật để không phát sinh chi phí ngầm.
- `POST .../learner/assignments/{assignmentId}/qa` bắt buộc `Idempotency-Key`; reserve `QA_QUERY` trước provider call, retrieval tenant/revision-scoped, response có citations hoặc explicit insufficient-evidence refusal.
- `POST .../feedback` và `POST .../reports`.
- `POST .../courses/{courseId}/completion-rule|publish`, `POST .../lessons/{lessonId}/prerequisites` cho author; `POST .../certificates/{certificateId}/revoke` chỉ OWNER/ADMIN và bắt buộc lý do.
- `GET .../learner/paths`, `POST .../learner/paths/{courseId}/cohorts/{cohortId}/certificate`; certificate chỉ cấp khi mọi lesson đạt passing score và delayed recall nếu buyer bật.
- `GET /api/v1/certificates/{verificationCode}` là public verification tối giản, không trả internal user/course/cohort ID. Đây là certificate hoàn thành nội bộ, không phải chứng chỉ được công nhận.

### Billing

- `GET /api/v1/billing/plans`, `GET .../usage`, `GET .../invoices`, `GET .../refunds`.
- `POST .../checkout-sessions` tạo payOS link từ server-side price; catalog phân biệt
  `SUBSCRIPTION` và prepaid `TOP_UP`. Top-up chỉ mở khi thuê bao còn hiệu lực quá TTL
  checkout, cộng atomically vào entitlement hiện tại và có `credit_grants` để đối soát.
- `POST .../subscriptions/{id}/cancel` dừng cuối kỳ. Reconciliation task tạo duy nhất một
  renewal invoice/payment link trước 7 ngày; subscription quá hạn đi qua grace period
  `PAST_DUE` 7 ngày trước khi `EXPIRED`.
- `POST .../refunds` chỉ nhận yêu cầu hoàn toàn bộ top-up còn đủ credit. Vì payOS không có
  refund API, internal task chỉ xác nhận sau khi operator đã hoàn tiền qua ngân hàng và nhập
  provider reference; lúc đó invoice/payment/order và credit được thu hồi trong một transaction.
- `POST /api/v1/webhooks/payos` là public webhook riêng, không dùng user auth nhưng bắt buộc signature/inbox dedupe.

### Analytics/export/integration

- `GET .../activation` trả checklist kích hoạt được suy ra từ dữ liệu canonical: source,
  package revision, course, learner, program launch và learner completion. Response kèm trạng thái
  paid/trial, hạn trial và next action; không nhận trạng thái hoàn thành do client tự khai báo.
- `GET .../analytics/overview|cohorts` tổng hợp activation, completion, practice score,
  delayed-recall score, learner feedback và lỗi nội dung từ dữ liệu server canonical.
- `GET .../analytics/cohorts/{cohortId}/insights` trả điểm yếu theo exact assessment
  snapshot/revision; mọi truy vấn đều tenant-scoped và cohort không thuộc tenant trả 404.
- `GET .../analytics/cohorts.csv` xuất báo cáo permissioned, escape RFC 4180 và chặn
  spreadsheet-formula injection. Denominator là số assignment-recipient đã publish/closed;
  timezone báo cáo là `Asia/Ho_Chi_Minh` và timestamp nguồn vẫn lưu UTC.
- `GET .../analytics/profitability` chỉ cho OWNER/ADMIN, đối soát cash/refund với doanh thu
  subscription phân bổ theo kỳ và top-up tại ngày thu tiền; chi phí AI dùng cả actual và
  shadow price. Kỳ refund có thể tạo doanh thu âm, không bị clamp để làm đẹp dashboard.
- `GET|PUT .../analytics/profitability/assumptions` quản lý tỷ giá, payment fee, hạ tầng,
  support, tax reserve, CAC và logo churn. Trạng thái luôn `UNCONFIGURED` đến khi buyer-side
  operator xác nhận giả định; LTV/CAC và payback không hiện nếu thiếu mẫu số hợp lệ.
- `POST .../analytics/profitability/costs` ghi khoản trực tiếp ngoại lệ có audit trail;
  không dùng để ghi lại chi phí support/infra đã nằm trong phân bổ tháng.
- `GET .../analytics/costs` với filter có limit.
- `GET .../packages/{packageId}/exports/markdown|word` xuất exact current revision cho staff cùng
  tenant, kèm đáp án và timestamp evidence; response luôn `private, no-store` và ghi audit.
  Markdown là portable baseline. Word là `.docx` OpenXML và yêu cầu Training Team/Business còn
  hiệu lực; kiểm tra entitlement ở server, client không thể tự mở paywall.
- Business integration management (OWNER/ADMIN, JWT):
  - `GET|POST .../integrations/api-keys`, `DELETE .../integrations/api-keys/{keyId}`.
    Token `v2k_live_*` chỉ trả một lần, server chỉ lưu SHA-256; scope allowlist hiện tại là
    `catalog:read` và `analytics:read`, expiry tối đa 365 ngày, tối đa 10 key active/account.
  - `GET|POST .../integrations/webhook-endpoints`, `DELETE .../{endpointId}`,
    `POST .../{endpointId}/rotate-secret`, `GET .../{endpointId}/deliveries`. Signing secret
    `whsec_*` chỉ trả một lần, AES-256-GCM at rest với version/AAD; tối đa 10 endpoint active/account.
- Business data API dùng `Authorization: Bearer v2k_live_*` tại
  `/api/v1/integrations/v1/organizations/{organizationId}/courses|analytics/overview|analytics/cohorts`.
  Key bị tenant-bind, scope-check, rate-limit và ngừng hoạt động ngay khi revoke/expire hoặc subscription
  Business không còn active. Owner/Admin vẫn xem và revoke/disable credential cũ sau khi gói hết hạn;
  chỉ create/rotate yêu cầu Business active. Không dùng API key thay user JWT trên API quản trị.
- `GET /api/v1/integrations/openapi.json` là OpenAPI 3.1 contract công khai, immutable trong major
  version và cache một giờ. CI contract test chặn drift giữa scope/event allowlist với tài liệu;
  breaking change phải phát hành namespace `/v2`, không sửa âm thầm `/v1`.
- Webhook chỉ nhận public HTTPS port 443; resolve và chặn loopback/private/link-local/CGNAT/ULA lúc tạo
  và trước mỗi attempt để giảm SSRF/DNS-rebinding risk. Không follow redirect. Envelope có
  `id`, `type`, `version`, `occurredAt`, `organizationId`, `data`; header gồm `X-V2K-Delivery`,
  `X-V2K-Event`, `X-V2K-Timestamp`, `X-V2K-Signature`.
- Chữ ký là `v1=hex(HMAC-SHA256(secret, timestampEpochSeconds + "." + exactRawBody))`. Consumer phải
  constant-time compare, từ chối timestamp lệch quá 5 phút và dedupe `X-V2K-Delivery`. Mỗi outbox
  event/endpoint chỉ tạo một delivery; 408/425/429/5xx retry bounded, 4xx khác dead-letter ngay.

## 5. Internal API

- `POST /internal/tasks/analysis/{jobId}`.
- `POST /internal/tasks/outbox/dispatch`.
- `POST /internal/tasks/notifications/dispatch`.
- `POST /internal/tasks/billing/reconcile`.
- `POST /internal/tasks/reviews/schedule`.
- `POST /internal/tasks/retention/cleanup`.

Retention cleanup xóa idempotency đã hết hạn; redaction raw Gemini output và processed payment
webhook body/signature; xóa outbox, notification, invitation và metadata source upload terminal theo
configurable window. Object tạm nằm dưới `pending-source-uploads/` và bắt buộc có R2 lifecycle riêng.
Webhook provider/event key, package canonical, financial/cost ledger, audit và learning evidence không
bị xóa bởi operational cleanup này. Terminal outbound webhook delivery giữ 30 ngày; API key hết hạn/thu
hồi, endpoint disabled và secret version cũ giữ 90 ngày nếu không còn delivery tham chiếu. Billing
reconciliation gọi cùng cleanup để không thêm Scheduler job.

Organization trial mới tự xếp ba job dedupe: chào mừng ngay, nhắc activation sau 2 ngày và cảnh báo
hết trial trước 3 ngày. Dispatcher hiện hữu xử lý cả job đến hạn nên không phát sinh Scheduler/service
mới. Trước lúc gửi, worker kiểm tra lại trạng thái user/member/organization, preference hiện hành,
learner completion và paid subscription; email không còn phù hợp bị cancel + redact, không gọi provider.

Cùng notification cycle sẽ phát hiện assignment mới, deadline trong 7 ngày và flashcard đã đến hạn rồi
xếp job theo assignment/user hoặc user/ngày. Assignment availability chỉ pre-schedule tối đa 30 ngày;
deadline gửi trước 24 giờ; review digest gửi lúc 08:00 `Asia/Ho_Chi_Minh`. Trước provider call, dispatcher
kiểm tra lại membership, preference, assignment/progress/deadline và số thẻ hiện còn đến hạn. Endpoint
`/internal/tasks/reviews/schedule` cho phép operator chạy riêng cùng logic idempotent khi recovery.

### Privacy

- `GET /api/v1/privacy/export` trả JSON portable của đúng authenticated subject, với
  `Cache-Control: no-store`; bao gồm notification preference hiện hành và consent history, không cho
  owner xuất dữ liệu học tập của người khác qua endpoint này.
- `POST|DELETE /api/v1/privacy/deletion-request` lên lịch/hủy xóa trong grace period 7 ngày.
  Sole owner phải chuyển ownership trước. Maintenance task pseudonymize identity và membership,
  redact Q&A/email payload, giữ ledger cần cho tài chính dưới pseudonymous UUID và lưu SHA-256
  identity block để JWT cũ không thể tự tạo lại account.
- Việc xóa identity trong Supabase Auth vẫn là bước operator bắt buộc sau khi local request hoàn tất;
  application block bảo đảm identity còn sót ở IdP không lấy lại quyền truy cập.

### Legal acceptance

- `GET /api/v1/legal/manifest` công khai type/version/URL của Terms, Privacy, AUP và AI notice.
- `GET /api/v1/legal/status` và `POST /api/v1/legal/acceptances` provision authenticated identity,
  kiểm tra exact policy set và ghi immutable acceptance với hashed request evidence; retry là idempotent.
- Production chặn mọi authenticated business API bằng HTTP 428 cho tới khi current version set được
  chấp nhận; vẫn cho phép `/me`, legal, privacy, public certificate, health và signed webhook flows.
- Đổi bất kỳ policy version nào tự động yêu cầu re-consent. Production từ chối boot nếu policies chưa
  được owner đánh dấu legal-reviewed hoặc URL không phải HTTPS; placeholder trong frontend chỉ dùng dev.

Chỉ Cloud Tasks/Scheduler service account được gọi; kiểm tra OIDC audience/issuer/service-account email. Handler luôn idempotent và trả 2xx cho event đã xử lý.

## 6. HTTP contract

- Cursor pagination, không offset cho bảng tăng liên tục.
- Filter/sort allowlist; page size có hard maximum.
- `ETag/If-Match` cho editable resource.
- `Idempotency-Key` bắt buộc cho tạo analysis, checkout, publish, submit attempt và export.
- Correlation ID trả response và propagate đến task/provider.
- OpenAPI là contract; generated TypeScript client hoặc schema-derived types để tránh DTO drift.
- Version API bằng URL cho breaking contract; package/schema/model version độc lập.

## 7. Domain events tối thiểu

- `OrganizationCreated`, `MemberInvited`, `InvitationAccepted`.
- `SourceRegistered`, `AnalysisRequested`, `AnalysisCompleted`, `AnalysisFailed`.
- `PackageApproved`, `PackagePublished`, `QualityIssueReported`.
- `AssignmentPublished`, `LearnerStarted`, `AssessmentSubmitted`, `DelayedRecallCompleted`, `AssignmentCompleted`, `FlashcardReviewed`.
- `PaymentReceived`, `PaymentRefunded`, `SubscriptionActivated`, `SubscriptionPastDue`, `EntitlementChanged`.
- `UsageReserved`, `UsageCommitted`, `UsageReleased`, `BudgetThresholdReached`.

Event envelope: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `organizationId`, `actorId`, `aggregateType`, `aggregateId`, `correlationId`, `causationId`, `payload`. Consumer phải hỗ trợ duplicate.

## 8. Product analytics events

- `signup_completed`, `organization_created`, `source_submitted`.
- `analysis_completed|failed`, `package_edited|approved|published`.
- `learner_invited|activated`, `assignment_started|completed`.
- `quiz_started|submitted`, `flashcard_reviewed`, `delayed_review_completed`.
- `qa_asked`, `citation_opened`, `content_reported`.
- `pricing_viewed`, `checkout_started`, `payment_completed`, `plan_upgraded|downgraded|cancelled`.

Mỗi event có schema, owner, purpose, allowed properties, retention và metric consumers. Không gửi PII hoặc raw learning content.

## 9. Test matrix

- Unit: domain transition, quota, money, FSRS, prompt/schema validator.
- Repository: Testcontainers + real Flyway/PostgreSQL/pgvector.
- API: auth/role/tenant negative cases, idempotency, ETag, pagination và Problem Details.
- Contract: WireMock cho Gemini/payOS/Resend; fixture cho signature và malformed response.
- Workflow: Awaitility cho outbox/task/job; duplicate/out-of-order webhook; retry/circuit breaker.
- Frontend: MSW happy/error/loading/empty/permission states; accessibility axe.
- E2E Playwright: creator publish, learner complete, payment activate, tenant isolation.
- Performance: k6 cho API read, job creation, webhook burst; worker soak test theo DB connection/provider limit.
- Security: dependency/container scan, secret scan, IDOR/tenant suite, upload validation, prompt injection và webhook replay.
- Recovery: backup restore, migration rollback compatibility, provider outage và task backlog drill.
