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
- `notification_jobs`, `notification_deliveries`, `notification_preferences`.
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
- `GET/POST /api/v1/organizations`
- `GET/PATCH /api/v1/organizations/{orgId}`
- `GET/POST /api/v1/organizations/{orgId}/members`
- `PATCH/DELETE /api/v1/organizations/{orgId}/members/{userId}`
- `POST /api/v1/organizations/{orgId}/invitations`
- `POST /api/v1/invitations/{token}/accept`

### Catalog/authoring

- CRUD `/organizations/{orgId}/courses`, modules và lessons.
- `POST .../sources` validate source và rights attestation.
- `POST .../analyses` với `Idempotency-Key`; trả `202` + job URI.
- `GET/POST .../analysis-jobs/{jobId}` cho status/cancel/retry hợp lệ.
- `GET/PATCH .../packages/{packageId}/draft` dùng ETag/If-Match.
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
- `POST .../exports`, `GET .../exports/{id}` trả presigned URL khi ready.
- CRUD API keys/webhooks cho plan cho phép.

## 5. Internal API

- `POST /internal/tasks/analysis/{jobId}`.
- `POST /internal/tasks/outbox/dispatch`.
- `POST /internal/tasks/notifications/dispatch`.
- `POST /internal/tasks/billing/reconcile`.
- `POST /internal/tasks/reviews/schedule`.
- `POST /internal/tasks/retention/cleanup`.

### Privacy

- `GET /api/v1/privacy/export` trả JSON portable của đúng authenticated subject, với
  `Cache-Control: no-store`; không cho owner xuất dữ liệu học tập của người khác qua endpoint này.
- `POST|DELETE /api/v1/privacy/deletion-request` lên lịch/hủy xóa trong grace period 7 ngày.
  Sole owner phải chuyển ownership trước. Maintenance task pseudonymize identity và membership,
  redact Q&A/email payload, giữ ledger cần cho tài chính dưới pseudonymous UUID và lưu SHA-256
  identity block để JWT cũ không thể tự tạo lại account.
- Việc xóa identity trong Supabase Auth vẫn là bước operator bắt buộc sau khi local request hoàn tất;
  application block bảo đảm identity còn sót ở IdP không lấy lại quyền truy cập.

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
