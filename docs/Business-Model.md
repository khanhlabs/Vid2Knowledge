# Vid2Knowledge — Business Model and Profitability Gates

## 1. Commercial thesis

The primary payer is a Vietnamese small training provider or cohort-based academy with 2–20 instructors, paying learners, recurring cohorts, and an authorised video library. Educational creators are the secondary segment. Vid2Knowledge sells reduced authoring effort plus measurable learner completion and comprehension. Learners consume assigned packages without payment during the initial model.

The product does not compete on summary generation alone. Its defensible workflow combines authorised content, reusable course structure, assessed question quality, assignments, and longitudinal learner mastery data.

## 2. Initial offer

The first sale is a fixed-scope concierge pilot covering an agreed number of video minutes, one learner cohort, generated learning packages, quality review, assignment, and an outcome report. Concierge delivery validates the offer; the target system subsequently productises the full recurring buyer, learner, billing, analytics, integration, and enterprise workflow.

A qualified pilot must have a buyer with authority, authorised content, a scheduled learner cohort, a defined success outcome, and payment or a signed purchase commitment.

## 3. Acquisition hypothesis

Test no more than two channels at a time, initially founder-led outreach and partnerships in creator/training communities. Track contact → response → qualified meeting → pilot proposal → paid pilot → recurring account. A channel is not validated by traffic or sign-ups alone.

Each buyer's audience is the primary learner-distribution loop. Public learner acquisition is deferred until a separate B2C model proves retention and CAC.

## 4. Unit economics

Use video minutes or credits as the cost unit; never use an unqualified “number of videos” and never promise unlimited processing.

```text
Monthly contribution margin per account
= recognised subscription and usage revenue
- payment fees
- AI generation, retry, regeneration, and chat cost
- directly attributable infrastructure and storage
- onboarding and customer-support labour
- refunds and credits
```

```text
Contribution LTV = monthly contribution margin / monthly logo churn
LTV/CAC = contribution LTV / fully loaded acquisition cost
CAC payback months = acquisition cost / monthly contribution margin
```

The model must show base, adverse, and high-usage cases. Gemini YouTube URL preview is assigned a conservative shadow price based on video tokens/minute and a safety multiplier. Free preview pricing is never counted as durable margin.

The in-product account P&L uses reconciled payment/refund data, prorated subscription revenue,
paid top-ups, shadow AI cost, payment fees, allocated infrastructure/support, exceptional direct
costs, tax reserve, CAC and logo churn. Default assumptions are placeholders and can never yield a
green status until an OWNER/ADMIN explicitly confirms them against contracts and invoices. Refunds
remain visible in the period in which they are resolved, including a negative-revenue period.

## 5. Provisional commercial gates

These thresholds are hypotheses to revise with evidence, not promises:

- Phase 0: at least 3 paid pilots or signed purchase commitments from qualified buyers.
- Concierge pilot: at least 2 buyers repeat on another video/cohort and at least 1 renews, expands, or signs a follow-on commitment.
- Productisation: positive contribution margin in the adverse-usage case.
- Acquisition scale: contribution LTV/CAC at least 3, CAC payback no more than 12 months, and paid retention measured over a meaningful contract period.
- No single provider price change in the adverse model may make every active paid offer contribution-negative.

## 6. Versioned platform assumptions

As of 2026-09-06, Gemini's direct YouTube URL input is a preview capability, supports public videos only, and its current no-charge treatment and rate limits may change. The current product must therefore treat it as a replaceable processing adapter and apply shadow pricing in every forecast. Revalidate this assumption before each commercial release against the official [Gemini video-understanding documentation](https://ai.google.dev/gemini-api/docs/video-understanding) and [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing).

If YouTube API Services are later used for metadata, embedding, or account data, perform a policy review against the current [YouTube API Services Developer Policies](https://developers.google.com/youtube/terms/developer-policies). Do not infer permission to download, cache, or redistribute audiovisual content from the fact that a video is publicly viewable.

## 7. Buyer ROI evidence

For each pilot record authoring hours saved, learner activation/completion, quiz quality defects, delayed recall where feasible, support time, buyer satisfaction, renewal intent, actual payment, and reason for expansion or churn. Product usage without buyer ROI is insufficient evidence.

## 8. Revenue expansion model

Revenue must be designed to expand with demonstrated customer value, not arbitrary feature locking:

- More authorised source minutes and generated packages.
- More active learners, cohorts, courses, instructors, or workspaces.
- Advanced mastery analytics and outcome reporting.
- Higher-cost Ask Course, regeneration, and verification workloads.
- Branding, API/webhooks, LMS integrations, SSO, audit, retention controls, SLA, and priority support.
- Annual prepayment and minimum commitments that improve cash flow and reduce churn.

Track gross revenue retention, net revenue retention, expansion MRR, contraction, logo churn, revenue concentration, and service cost by account. No custom contract is approved without an owner, margin model, support boundary, and a path to reusable product capability.

## 9. Stop and pivot rules

- If buyers like the demo but will not pay, change the buyer/problem/offer before building administration features.
- If learners do not activate or retain knowledge, improve the learning design rather than adding output formats.
- If support and AI costs erase contribution margin, narrow supported content, reduce entitlement, or reprice.
- If acquisition is not repeatable, do not compensate with paid advertising before fixing the channel and positioning.
- If platform or content-policy risk cannot be accepted, pivot toward buyer-provided source material through a compliant input path.

## 10. Giả thuyết giá cho thị trường Việt Nam

Đây là mức giá để kiểm chứng, chưa phải bảng giá cam kết. Giá cuối cùng phải dựa trên buyer interview, actual usage, support cost và willingness-to-pay.

| Gói | Giá kiểm chứng | Phạm vi giả định | Mục tiêu |
|---|---:|---|---|
| Paid pilot | 5–15 triệu VNĐ/lần | 300–600 phút nguồn, một cohort, human QA và báo cáo outcome | Chứng minh buyer chịu trả tiền và đo ROI |
| Creator | 790.000 VNĐ/tháng hoặc 7,9 triệu/năm | 300 phút nguồn/tháng, 3 cohort, 200 active learners | Entry offer và creator-led distribution |
| Training Team | 2,49 triệu VNĐ/tháng hoặc 24,9 triệu/năm | 1.500 phút nguồn, 10 instructor, 1.000 active learners, branding và analytics | Gói doanh thu chính |
| Business | Từ 7,99 triệu VNĐ/tháng, ưu tiên hợp đồng năm | 5.000 phút, 50 instructor, 5.000 active learners, API/SSO/audit/SLA có giới hạn | Expansion và enterprise margin |

- Annual discount tối đa tương đương hai tháng, trừ khi lower churn/CAC chứng minh mức khác tốt hơn.
- Promotion tự phục vụ có hard ceiling theo channel: referral 15%, partner 20%, sales 25%, retention
  30%. Đây là trần, không phải mức mặc định; operator phải so attributed revenue, discount granted,
  CAC/payback và renewal theo cohort trước khi tăng. Không cấp mã 50% qua API dù database có safety
  ceiling 50% cho rolling compatibility.
- Không bán lifetime deal và không dùng từ “unlimited”.
- Overage dùng credit pack trả trước; giá sàn phải bao phủ p95 shadow cost, payment, support và contribution margin mục tiêu.
- Custom integration/SLA tính setup fee hoặc minimum annual commitment riêng.
- payOS xử lý payment không thay thế nghĩa vụ hợp đồng, thuế hoặc hóa đơn; cần kế toán/tư vấn pháp lý trước commercial launch.

## 11. Mục tiêu lợi nhuận

- Gross margin sau AI, hạ tầng, storage, email và payment: tối thiểu 75% ở base case và 60% ở adverse case.
- Contribution margin sau onboarding/support trực tiếp: tối thiểu 60% khi account đã qua onboarding.
- Chi phí AI của một account không vượt 15% doanh thu account; cảnh báo ở 10%, hard review ở 15%.
- Contribution LTV/CAC tối thiểu 3; mục tiêu 5 sau khi channel ổn định.
- CAC payback tối đa 12 tháng; mục tiêu 6 tháng với founder-led/partner channel.
- Net revenue retention mục tiêu trên 100% sau khi có đủ cohort; gross revenue retention mục tiêu tối thiểu 85%/năm cho SMB training.
- Không scale paid acquisition khi chưa có ít nhất 10 account trả tiền và 3 tháng retention signal.

## 12. Sales funnel đầu tiên

1. Lập danh sách 100 đơn vị đào tạo phù hợp, ghi rõ lĩnh vực, số giảng viên, cohort, video library, buyer và warm-intro path.
2. Founder-led outreach cá nhân hóa 20–30 account/tuần; không spam hàng loạt.
3. Discovery theo ROI: giờ soạn bài, số video, số learner, completion, chi phí nhân sự, churn học viên và quy trình đo kết quả.
4. Demo bằng chính một video của buyer; đề xuất paid pilot có scope, deadline, outcome và price rõ.
5. Sau pilot, business review bằng số giờ tiết kiệm, activation, completion, learning result, support và kế hoạch cohort tiếp theo.
6. Chuyển sang annual Training Team; dùng referral/partner code có thời hạn và capacity hữu hạn để
   buyer/creator giới thiệu account mới. Scale một code chỉ khi contribution LTV/CAC đạt ít nhất 3 và
   payback không quá 12 tháng; lượt redemption không thay thế retention/renewal evidence.

Funnel source of truth nằm trong database/CRM table hoặc CRM được chọn sau; PostHog không thay thế sales ledger. Founder time phải được shadow-cost vào CAC để tránh ảo tưởng kênh acquisition miễn phí.

## 13. Kiểm toán khả năng sinh lời

Hướng B2B2C này có xác suất sinh lợi cao hơn B2C sinh viên đại trà vì buyer có ngân sách, mang theo nhiều learner và có expansion path. Tuy nhiên lợi nhuận chưa được coi là chứng minh cho đến khi đồng thời đạt paid pilot, repeat usage, renewal, learning outcome, adverse-case contribution margin và repeatable acquisition.

Rủi ro lớn nhất theo thứ tự là: buyer không trả tiền; buyer cần LMS/custom service vượt product scope; learner không dùng; chất lượng quiz làm mất niềm tin; phụ thuộc Gemini preview; sales/support cost cao; và compliance dữ liệu. Các gate trong `Plan.md` được thiết kế để phát hiện đúng các rủi ro này trước khi scale chi phí.
