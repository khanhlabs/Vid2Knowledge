# Vid2Knowledge — Product Strategy and Target Scope

## 1. Product thesis

Vid2Knowledge helps a learner turn a long authorised educational video into a verifiable study loop: understand the material, practise it, identify weak areas, and return to review it. It is not positioned as a generic AI video summarizer.

### Initial buyer, user, and job to be done

**Primary buyer:** Vietnamese small training providers and cohort-based academies with 2–20 instructors, paying learners, and an owned or authorised library of educational videos. They have clearer budgets, recurring cohorts, measurable completion problems, and higher expansion potential than individual learners.

**Secondary buyer:** Educational creators and independent instructors with an existing paid community. This segment uses the same product but does not dilute the first sales message.

**Initial user:** The buyer's learners. Individual learners are users of the learning experience, but are not the primary payer during the first commercial validation.

**Buyer job to be done:** “Turn my existing video library into verifiable, interactive learning material without repeatedly writing notes, cards, and quizzes by hand, then show me whether learners complete and understand it.”

**Learner job to be done:** “Help me understand, practise, and retain the material in a long lesson, with a quick path back to the relevant source.”

The first commercial beta must recruit this buyer profile only. A broad self-serve student product remains a later expansion bet. This choice is intended to improve willingness to pay, recurring usage, content permission, and distribution through each buyer's existing audience.

### Value proposition

- Buyers reduce the time required to turn an existing video into usable learning material.
- Learners receive structured notes, source links, and active-recall practice.
- Buyers can assign a package and see completion and comprehension signals.
- The product gains distribution through buyers who already reach learners.

## 2. Product principles

- **Evidence over fluent text.** Any timestamp is a claim about the source and must be shown as a link to that source. The UI must state when a timestamp or answer is unavailable or uncertain.
- **Learning outcome over content generation.** Build features that increase comprehension, recall, and return learning; do not prioritise cosmetic export features over those outcomes.
- **Cost is a product constraint.** Every paid or free entitlement has a measurable AI cost, a hard limit, and a user-visible explanation.
- **One stable contract.** AI output is versioned JSON, validated before storage or display, and never treated as factual without source context.
- **Least data and clear rights.** Store only data needed to deliver the service. Cached material is scoped by an explicit sharing policy; it is never silently exposed to another user.

## 3. Product outcome and delivery strategy

The product succeeds when an authorised buyer can turn a supported video library into assignable learning programmes, learners measurably improve, and the buyer renews or expands at a positive contribution margin.

The target product is deliberately complete for the chosen market. Delivery remains phased so each investment is informed by evidence, but time-to-build is not used to remove capabilities that materially improve revenue, retention, expansion, defensibility, or operating margin.

The platform does not become a generic LMS. Every major capability must strengthen the authorised video-to-learning workflow, buyer outcome measurement, learner mastery, or commercial operations.

## 4. Learning package contract

The package must include source-verification metadata and schema versioning:

```json
{
  "schemaVersion": "learning-package-v3",
  "video": { "sourceType": "YOUTUBE", "sourceId": "...", "youtubeUrl": "https://www.youtube.com/watch?v=...", "videoId": "...", "title": "...", "language": "vi" },
  "summary": { "overview": "...", "sections": [{ "id": "section-one", "title": "...", "content": ["..."], "source": { "timestampSeconds": 0, "evidence": "..." } }] },
  "keyTakeaways": [{ "id": "takeaway-one", "text": "...", "source": { "timestampSeconds": 0, "evidence": "..." } }],
  "flashcards": [{ "id": "card-one", "question": "...", "answer": "...", "source": { "timestampSeconds": 0, "evidence": "..." } }],
  "quiz": [{ "id": "quiz-one", "question": "...", "options": ["...", "...", "...", "..."], "correctAnswerIndex": 0, "explanation": "...", "source": { "timestampSeconds": 0, "evidence": "..." } }]
}
```

The actual schema specifies required fields and server-side size/count/source validation. Revision `verificationState` is assigned by the workflow or a human reviewer, not by model self-confidence. Every learner-visible item must retain a validated timestamp and evidence string.

## 5. Scope by priority

### MVP baseline

| Capability | User outcome | Acceptance criterion |
|---|---|---|
| Supported YouTube URL intake | Learner knows immediately whether a video can be processed. | Canonical ID validation and actionable errors for unsupported, unavailable, or over-limit videos. |
| Durable analysis job | Learner can leave and return without losing work. | Idempotent creation, queued/processing/completed/failed/cancelled states, bounded retries, and recoverable failures. |
| Evidence-linked notes | Learner can verify material against the source. | Every returned timestamp opens the original video; uncertainty is visible. |
| Flashcards and quiz | Learner practises recall. | Answers are graded, explanations shown, and completion recorded. |
| History and feedback | Learner returns; team can learn from failure. | Packages reopen without regeneration; helpful/not-helpful and report-error feedback are stored. |
| Authentication and entitlements | Usage is attributable and costs are protected. | Quota check is atomic before job reservation; rate limits protect account and IP. |
| Buyer ownership and assignment | A buyer can organise and give a package to a defined learner cohort. | Buyer attests content rights; package access is permissioned; assignment link and completion state work end to end. |
| Outcome dashboard | Buyer can judge whether the package was used. | Shows assigned, started, completed, quiz score, and learner feedback without inventing YouTube-derived metrics. |

### Learner retention system

1. **Spaced-repetition review:** known/again state, next review date, adaptive review queue, streak, reminder preferences, and mastery history.
2. **Exam mode:** practice sets filtered by topic/difficulty, weak-area results, source-linked explanations, retakes, and delayed recall.
3. **Learning paths:** playlists and video libraries become ordered modules with prerequisites, progress, deadlines, and completion rules.
4. **Ask Video/Course:** source-grounded answers with timestamps, citations, uncertainty handling, usage limits, and teacher controls.

All four belong to the target product. Evidence determines implementation order, entitlement, and packaging—not whether profitable retention capabilities are permanently excluded.

### Buyer and commercial platform

- Multi-tenant organisations, workspaces, roles, audit log, content ownership, approval workflow, reusable templates, branding, cohort management, assignments, deadlines, and learner invitations.
- Editable AI output with version history, human approval, regeneration controls, quality reports, duplicate detection, and reusable question banks.
- Outcome dashboards for activation, completion, attempts, topic mastery, delayed recall, cohort comparison, and exportable reports.
- Buyer ROI dùng dữ liệu assessment/progress phía server làm nguồn sự thật, công bố rõ
  denominator và timezone; CSV export có phân quyền và không thể kích hoạt công thức bảng tính.
- Self-serve trial, subscription billing, invoicing, credits/overages, promotion/referral attribution,
  dunning, cancellation, refund, entitlement, and revenue analytics. Promotion đã có server-side quote,
  immutable price snapshot, capacity reservation, one-redemption-per-organization, channel-specific
  margin ceiling và attributed revenue/discount reporting. Tax-ready buyer profile đã có version/audit,
  immutable invoice snapshot và safe CSV handoff cho kế toán. Đây vẫn là chứng từ nội bộ, không tự nhận là
  hóa đơn điện tử hợp pháp; proration và kết nối nhà cung cấp hóa đơn điện tử còn là phần việc trước
  production commercial.
- Integrations through API/webhooks plus evidence-led LMS/SSO connections for higher-value accounts.
- In-app onboarding dùng sáu bằng chứng server-side từ source đến learner completion; trial deadline,
  next-best action và annual-saving upgrade surface giúp buyer đi tới giá trị đầu tiên mà không tạo
  metric ảo từ click phía client. Email vòng đời chào mừng/nhắc activation/sắp hết trial dùng cùng
  durable queue, tự dừng khi buyer đã đạt mục tiêu hoặc đã trả tiền, và có opt-out hướng dẫn sản phẩm.
  Nhắc assignment mới, deadline trước 24 giờ và digest thẻ đến hạn lúc 08:00 dùng dữ liệu học tập
  canonical, dedupe và tự hủy nếu learner đã hoàn thành/opt-out. Marketing mặc định tắt và có consent
  ledger. Referral/partner attribution đã nối vào checkout/invoice. Support diagnostics đã có explicit
  OWNER grant tối đa 24 giờ, read-only aggregate data và immutable access trail; không impersonate.
  Sample course tiếp tục được triển khai theo cùng funnel.
- Markdown portable và Word OpenXML đã có controlled tenant export/audit; Word là Training
  Team/Business differentiator. PDF/controlled sharing chỉ thêm khi pilot chứng minh buyer cần,
  tránh vận hành renderer/storage bất đồng bộ trước khi tạo doanh thu.

### Supported-source boundary

Private and unlisted YouTube URLs remain unsupported; the product never circumvents YouTube access controls.
Training Team và Business buyers can instead upload their own MP4/WebM through a private, rights-attested
path. Objects are verified before ingestion, moved to a durable tenant key, and exposed to assigned learners
only through short-lived playback URLs. Documents/audio remain later supported-source extensions.

Native mobile remains evidence-led because it adds a separate distribution and maintenance surface; responsive/PWA learner use is required first.

## 6. Monetisation hypotheses

Learners access assigned material without paying. Revenue initially comes from a paid concierge pilot and then from the buyer account. No plan promises unlimited generation; entitlements use credits or processed video minutes.

| Offer | Outcome sold | Entitlements to test | Guardrail |
|---|---|---|---|
| Paid concierge pilot | Prove buyer ROI before productising administration | Team manually converts an agreed small library and supports one learner cohort | Fixed scope, upfront payment or signed purchase commitment |
| Creator | Repeatedly convert owned lessons and measure completion | Monthly processed-minute allowance, assignments, learner completion and quiz signals | Server-side entitlement and overage stop |
| Training team | Operate several courses or instructors | Seats, shared library, roles, cohorts, aggregated outcomes | Annual or monthly contract with explicit usage ceiling |
| Business/Enterprise | Standardise training and prove outcomes across teams | SSO, advanced roles, audit, retention controls, API/integrations, priority support | Annual contract, minimum commitment, scoped SLA |
| Credit add-on | Process unusually expensive usage | Additional video minutes, regenerate, later playlist analysis | Price exceeds p95 marginal cost plus target contribution margin |
| Individual Pro (later) | Retain material from public learning videos | Review queue, exam mode, personal history | Launch only after separate B2C CAC and retention validation |

Commercial validation starts before self-serve billing. Pricing is not approved until measured marginal cost, payment fees, support cost, sales/onboarding effort, cache-hit assumptions, and target contribution margin are documented. Export alone is not a paid value proposition.

Packaging follows a land-and-expand model: paid pilot → Creator/Training Team subscription → more processed minutes, cohorts, seats, courses, integrations, or enterprise controls. Discounts must be justified by lower churn, annual prepayment, lower service cost, or strategic distribution.

## 7. Trust, privacy, and content policy requirements

Before public beta, publish Terms of Service, Privacy Policy, acceptable-use rules, AI limitation notice, retention/deletion policy, and a content complaint process. Confirm the intended use of YouTube URLs and generated derivatives against applicable platform terms and counsel appropriate to target markets.

Product requirements:

- Users can delete their account and generated packages; retention windows are documented and enforced.
- Feedback/reporting does not leak package contents to unrelated users.
- Cache ownership, reuse consent, invalidation, and deletion behaviour are defined before cache sharing is enabled.
- No API key, OAuth token, or personally identifying information appears in client payloads, logs, analytics, or error messages.

## 8. Metrics and decision definitions

| Metric | Definition | Why it matters |
|---|---|---|
| Analysis success rate | Completed valid packages / accepted jobs, segmented by video type | Core reliability |
| Evidence accuracy | Human-audited factual and timestamp correctness | Product trust |
| Activation | User completes first quiz or marks first card, not merely creates a job | First realised value |
| D7 learning retention | Activated users who complete another review/study action on day 7 | Repeat value |
| Cost per completed package | AI + infrastructure cost / completed package | Unit economics |
| Gross margin by offer | Revenue less directly attributable costs | Monetisation viability |
| Helpful-rate / report rate | Feedback on opened packages | Quality and support signal |
| Qualified lead to paid pilot | Buyers paying or signing a purchase commitment / qualified leads | Willingness to pay |
| CAC and CAC payback | Acquisition cost and months of contribution needed to recover it | Scalable distribution |
| Contribution LTV/CAC | Contribution-value lifetime / acquisition cost | Sustainable growth |
| Paid retention and expansion | Buyers retained and increasing seats/usage | Recurring business value |
| Learning gain | Change between pre-test and delayed recall assessment | Outcome quality |

Target thresholds are set only after the feasibility baseline; each threshold, owner, measurement source, and decision consequence belongs in the delivery plan.
