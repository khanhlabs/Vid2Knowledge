# Vid2Knowledge — Production Operations

## Launch gate

Không nhận dữ liệu hoặc tiền thật cho tới khi owner xác nhận bằng chứng cho từng mục:

- Supabase production là Pro, đặt tại Singapore, PITR/daily backup hoạt động và đã restore thử vào database rỗng.
- GCP budget alert 50/80/100%, Gemini quota và Cloud Run max instance đã đặt; payOS vẫn off cho tới khi signed-webhook/reconciliation smoke test đạt.
- Cloudflare TLS, WAF/rate limit và Pages security headers đã bật; origin Cloud Run chỉ nhận frontend origin qua CORS.
- Resend domain xác thực, invitation/receipt/renewal test gửi thành công; encryption key có bản sao trong secret recovery process.
- Test cả ba email trial (welcome, activation nudge, trial expiry), link quay lại ứng dụng và trang
  preference. Xác nhận opt-out làm job đến hạn chuyển `CANCELLED`, payload thành `REDACTED` và không tạo
  request tới Resend; marketing phải giữ mặc định tắt cho account mới.
- Terms, Privacy, AUP, AI limitation, subprocessor list và complaint/takedown contacts đã được counsel duyệt với đúng pháp nhân vận hành.
- Các URL `LEGAL_*_URL` trỏ đúng bản HTTPS đã duyệt, version khớp nội dung, và chỉ sau đó mới đặt
  `LEGAL_REVIEWED=true`; thay nội dung phải tăng version để buộc re-consent.
- Có ít nhất hai người/địa chỉ nhận alert vận hành; không để production phụ thuộc duy nhất vào inbox cá nhân của founder.
- Cả hai notification channel đã xác thực email và alert drill API 5xx/queue backlog có bằng chứng nhận được thông báo.
- Nếu bật upload riêng tư: R2 bucket private, S3 token chỉ có object read/write/delete trên đúng bucket,
  CORS chỉ cho frontend origin với `PUT`, `GET`, `HEAD`, header `Content-Type`/`Range`, expose
  `Accept-Ranges`/`Content-Range`, và lifecycle xóa prefix
  `pending-source-uploads/` sau 1 ngày đã được kiểm thử.

## Backup and restore

Mỗi ngày kiểm tra trạng thái managed backup của Supabase. Mỗi tháng tạo thêm logical `pg_dump`
custom-format, mã hóa trước khi upload vào private object storage có lifecycle; không ghi database URL
hoặc password vào command history. Giữ ít nhất 3 monthly restore points, theo hợp đồng và retention
policy đã công bố.

Mỗi quý restore bản gần nhất vào project/database cô lập, chạy Flyway `validate`, smoke query số lượng
organization/payment/package và toàn bộ backend integration test. Ghi ngày, backup ID, người thực hiện,
thời gian restore và sai lệch; một file backup chưa restore thử không được tính là backup đã kiểm chứng.

RPO ban đầu 24 giờ, RTO 4 giờ. Nếu hợp đồng yêu cầu tốt hơn, nâng backup/PITR trước khi ký SLA.

## Incident runbooks

- Payment mismatch: không cấp entitlement; giữ inbox event/error code, đối chiếu payOS theo order code,
  amount và payment-link ID. Không sửa ledger trực tiếp ngoài audited adjustment.
- Provider outage: circuit breaker dừng retry không hữu ích; giữ reservation theo lease, mở lại qua
  reconciliation và theo dõi actual/shadow cost.
- Database outage: dừng mutation/worker, không fail-open quota. Sau restore chạy Flyway validate,
  reconciliation payment, outbox và notification trước khi mở traffic.
- Secret leak: vô hiệu/rotate tại provider trước, tạo secret version mới, deploy revision mới, kiểm tra
  audit logs và revoke các credential liên quan. Không commit secret đã lộ vào lịch sử.
- Privacy deletion: task local pseudonymize và block subject; operator xóa Supabase Auth identity,
  ghi ticket/bằng chứng hoàn tất. Financial/audit ledger chỉ còn pseudonymous ID theo retention policy.
- Business webhook dead-letter: xem delivery log theo endpoint, phân loại permanent 4xx với transient
  408/425/429/5xx, yêu cầu buyer sửa receiver trước khi rotate/re-enable. Không gửi lại thủ công bằng cách
  tạo event giả và không gửi payload sang ticket/chat. Key/secret lộ phải revoke/rotate ngay; pending
  delivery giữ secret version đã chốt để rotation không làm sai chữ ký giữa chừng.

## Monitoring and alert drill

Terraform dùng trực tiếp Cloud Monitoring metrics để không phải trả thêm APM vendor: API/worker 5xx lớn hơn
5 trong 5 phút là critical; Cloud Tasks non-OK attempt lớn hơn 5 trong 5 phút là critical; queue depth lớn
hơn 50 liên tục 10 phút là warning. Log-based metrics báo critical ngay khi AI circuit mở, payOS reconciliation
lệch, email hết retry hoặc Business webhook vào dead-letter. Mỗi alert phải có tối thiểu hai email
production đã xác thực.

Trước launch và mỗi quý, tạo lỗi có kiểm soát ở staging hoặc hạ threshold tạm thời bằng một reviewed
Terraform change, xác nhận cả hai người nhận thấy alert và recovery notification, rồi apply lại threshold
chuẩn. Lưu policy ID, thời điểm phát hiện, thời điểm acknowledge và thời gian xử lý. Không tạo traffic lỗi
trên production chỉ để thử alert.

Khi API/worker 5xx bắn: xem revision, request ID và deployment gần nhất; rollback revision nếu tương quan
rõ. Khi task failures bắn: pause queue trước nếu lỗi deterministic/provider-wide để tránh retry làm tăng
AI cost, sửa nguyên nhân rồi resume với dispatch limit thấp. Khi backlog bắn nhưng không có failures: kiểm
tra worker instance cap, DB pool và provider latency; không tăng concurrency trước khi xác nhận headroom DB.

Cloud Logging chỉ giữ INFO/WARN/ERROR ở production và không được log token, authorization header, provider
raw response hay payment payload. Production console dùng structured JSON để correlation ID và event marker
có thể truy vấn không cần parse tự do. Chỉ thêm APM trả phí khi native metrics không trả lời được một SLO có
ảnh hưởng doanh thu; review ingestion cost hàng tháng cùng P&L dashboard.

## Promotion and referral operation

Campaign chỉ được tạo qua `POST /internal/promotions` bằng Cloud Run service account/OIDC; không insert
trực tiếp vào database. Code, discount, channel, plan prefix và attribution reference là immutable để
không viết lại lịch sử economics. Nếu cấu hình sai, gọi `DELETE /internal/promotions/{campaignId}` để
deactivate rồi tạo campaign mới. `partnerReference` phải là opaque CRM/partner ID, tuyệt đối không chứa
PII. Giữ window/capacity nhỏ cho thử nghiệm đầu tiên; không dùng promotion cho top-up.

Trước khi mở rộng campaign, đối soát `reserved`, `redeemed`, `attributedRevenueVnd` và
`discountGrantedVnd` từ `GET /internal/promotions` với payment/invoice ledger. Reservation hết hạn không
chiếm capacity và được release atomically ở checkout tiếp theo; payment thành công mới được tính
redeemed/revenue. Dừng campaign ngay nếu discount làm account contribution margin dưới 60%, CAC payback
trên 12 tháng hoặc cohort không có renewal signal. Không đánh giá channel chỉ bằng số mã đã dùng.

Khi có payment mismatch, không sửa redemption/invoice bằng SQL. Chạy reconciliation; nếu order terminal,
reservation được release. Nếu cần correction tài chính, dùng audited adjustment/refund workflow và giữ
nguyên price snapshot ban đầu.

## Billing profile and accounting handoff

OWNER/ADMIN nhập đúng tên, địa chỉ và MST theo hồ sơ đăng ký của người mua trước checkout. Update dùng
profile version; lỗi `409` nghĩa là admin khác đã sửa và client phải reload, không retry mù. Invoice mới
snapshot profile đúng lúc record được tạo; invoice cũ không đổi khi profile thay đổi. CSV kế toán phải
được lấy từ endpoint có auth, không gửi qua public URL và không chỉnh số tiền/snapshot bằng spreadsheet.
Production luôn bật `billing.require-profile-before-checkout=true`; frontend cũng khóa nút thanh toán
đến khi profile đã được lưu. Renewal nội bộ vẫn chạy cho subscription cũ để tránh vô tình cắt doanh thu,
nhưng operator phải backfill profile cho mọi account đang hoạt động trước launch.

Đổi gói không tạo charge ngay. Buyer có thể thay/hủy lựa chọn đến trước lúc renewal checkout được tạo;
sau đó API trả `409` để operator không vô tình tạo hai order. Trong cửa sổ 7 ngày, reconciliation tạo
order theo plan kế tiếp; webhook đã xác thực mới tạo subscription `SCHEDULED` và đánh dấu gói cũ kết
thúc cuối kỳ. Khi buyer cần thêm capacity ngay, bán top-up thay vì sửa entitlement hoặc tính proration
thủ công. Không xóa renewal/order đã phát sinh bằng SQL; xử lý qua trạng thái thanh toán/refund có audit.

`V2K-*` là internal payment record, không phải hóa đơn điện tử hợp pháp. `taxDocumentRequested=true` chỉ
ghi nhận nhu cầu của buyer để kế toán xử lý; không được đổi wording thành “đã phát hành”. Trước khi nhận
tiền production, chọn nhà cung cấp hóa đơn điện tử, cấu hình seller identity/tax rate/signature, map
provider document ID về invoice và chạy accountant reconciliation. Quy định thay đổi phải được counsel/
kế toán xác nhận; tham chiếu hiện hành là Nghị định 254/2026/NĐ-CP trên Cổng TTĐT Chính phủ.

Billing profile chứa dữ liệu liên hệ tài chính. Log không được ghi request body; chỉ audit version và cờ
yêu cầu chứng từ. Snapshot invoice/financial ledger giữ theo retention pháp lý đã duyệt, không xóa theo
privacy cleanup thông thường. Chỉ OWNER/ADMIN được xem, sửa hoặc export.

## Explicit support diagnostics

Support không được dùng JWT của buyer, impersonation hoặc SQL console. OWNER tạo grant
`DIAGNOSTICS` trong app với reason, optional ticket và TTL 15 phút–24 giờ; có thể revoke ngay. Support
service account gọi internal diagnostics bằng exact organization/grant ID. Cả OIDC boundary và grant
đều phải hợp lệ; mỗi lần access lưu hash của service subject, không lưu raw credential.
Mỗi organization chỉ được có tối đa ba grant còn hiệu lực. Việc tạo grant được serialize theo
organization và lượt diagnostics giữ shared row lock trong transaction để revoke không thể bị race.

Diagnostics cố ý chỉ trả aggregate operational state: org/subscription, số member, analysis state 7
ngày, pending order, dead notification/webhook và last payment time. Không mở learner identity, nội dung
học, AI prompt/output, webhook/payment body hay secret. Nếu cần dữ liệu ngoài scope, OWNER phải cung cấp
artifact riêng; không nới endpoint tùy ticket. Grant hết hạn/revoked trả 403 và không có fail-open.
Response bắt buộc `Cache-Control: no-store`; reason/ticket không được chứa learner identity, nội dung
học hoặc dữ liệu cá nhân.

Khi xử lý ticket, support ghi grant ID trong ticket, lấy diagnostics một lần nếu đủ, rồi yêu cầu OWNER
revoke sớm. Hàng tháng review số grant, access/grant, thời gian sống và ticket thiếu reference; bất thường
phải được điều tra như privacy incident. Access event và grant giữ theo audit retention đã duyệt.

## Acquisition attribution

Không dùng raw UTM hoặc fingerprint làm nguồn sự thật. Khi tạo organization, backend chỉ chấp nhận
`DIRECT` hoặc `SAMPLE_COURSE` và ghi first-touch một lần; dữ liệu trước migration mang
`LEGACY_UNKNOWN`, không được tự gán là direct. Báo cáo internal `/internal/analytics/acquisition` đối
chiếu từng nguồn tới source/package/learner completion, organization từng trả tiền và net cash sau
refund. Chỉ tăng đầu tư cho sample khi paid conversion/contribution tốt hơn cohort direct đủ mẫu;
không tối ưu theo page interaction hoặc signup đơn thuần.

### Paid-pilot sales queue

Form `/pilot` gửi vào `POST /api/v1/public/pilot-leads` với idempotency và application rate limit 5
request/IP/phút; Cloudflare WAF/rate-limit là lớp phân tán bắt buộc. Khi bot abuse vượt lớp này, bật
Cloudflare Turnstile và chỉ backend verify token; không nhúng secret vào client. Sales operator lấy
Google ID token bằng cách impersonate Terraform output `sales_invoker_service_account`, audience bằng
`sales_oidc_audience`; task service account phải nhận 403 trên `/internal/sales/**`.

Lead `HOT`, `WARM`, `NURTURE` có SLA phản hồi lần đầu lần lượt 4/24/72 giờ. Queue đưa lead `NEW` quá hạn
lên trước rồi mới sắp theo priority/thời gian tạo; funnel đo overdue và average minutes-to-contact từ
immutable event đầu tiên, không dựa trên timestamp do client gửi. Chuyển state tuần tự và ghi lý do cụ thể khi `LOST`. Chỉ
đánh dấu `WON` sau khi organization thật đã được tạo; funnel khi đó đối chiếu toàn bộ payment trừ refund
thành công. Export PII ra spreadsheet/email bị cấm; response có `no-store`. Privacy version lưu cùng
consent phải là bản đã legal-review trước production, không được dùng `*-draft`.

## Data retention operation

Billing reconciliation gọi cùng transaction boundary của maintenance workflow cho retention cleanup;
operator cũng có thể gọi riêng `POST /internal/tasks/retention/cleanup` bằng Cloud Tasks OIDC. Defaults:
raw Gemini output 30 ngày, processed payOS payload/signature 90 ngày, terminal outbox/notification 30 ngày
và invitation terminal/hết hạn 90 ngày. Outbound webhook terminal giữ 30 ngày; integration credential
terminal/version cũ giữ 90 ngày. Idempotency record bị xóa ngay sau `expires_at`.
Source upload `REJECTED|EXPIRED` giữ metadata 30 ngày; object tạm không dựa vào database cleanup mà
dựa vào R2 lifecycle 1 ngày, nên upload bị bỏ dở không tích lũy storage cost.
Lead chưa chuyển thành khách (`NEW|CONTACTED|QUALIFIED|PROPOSAL|LOST`) không hoạt động 180 ngày được
redact tên/email/tổ chức/note/submitter evidence; event và source aggregate còn lại để đo funnel. Lead
`WON` theo customer/contract retention và không bị operational cleanup tự động redact.

Cleanup chỉ redaction payload và xóa operational record đã terminal. Nó cố ý giữ webhook provider/event
key để chống replay, package revision canonical, learning evidence, financial/cost ledger và audit log.
Không giảm retention các record hợp đồng/pháp lý nếu chưa có legal approval và migration riêng. Sau mỗi
lần đổi biến `RETENTION_*`, chạy staging cleanup trên snapshot và đối soát count trả về trước production.

Lifecycle email không có cron riêng: lúc tạo trial, API ghi ba `notification_jobs` với `available_at`
khác nhau; notification dispatcher hiện hữu claim chúng. Mỗi notification cycle cũng chạy scheduler
idempotent cho assignment/review; endpoint `/internal/tasks/reviews/schedule` chỉ dùng để recovery thủ
công, không cần Cloud Scheduler thứ tư. Review digest được xếp lúc 08:00 `Asia/Ho_Chi_Minh`; số thẻ được
tính lại ngay trước provider call. Theo dõi `PENDING/PROCESSING/DEAD/CANCELLED`
theo loại email. `CANCELLED` do opt-out/chuyển paid/đã activation là kết quả bình thường, không alert;
chỉ alert tỷ lệ `DEAD` hoặc backlog job đã quá `available_at`. Không gửi bù lifecycle email đã bị cancel.
`notification_preference_changes` là bằng chứng consent, không nằm trong cleanup notification terminal;
thời hạn giữ phải theo privacy policy và legal approval của công ty.

Mỗi pilot lead mới cũng ghi một `PILOT_LEAD_ALERT` trong cùng database transaction. Dedupe key gắn với
lead ID nên retry form không tạo alert thứ hai. Email tới `SALES_ALERT_RECIPIENT` chỉ chứa lead ID,
priority, acquisition source, thời điểm nhận và deadline phản hồi; founder phải mở sales queue bằng dedicated OIDC identity
để xem PII. Khi dispatcher gửi xong, payload mã hóa bị thay bằng `REDACTED`. Production/staging không được
bật `NOTIFICATIONS_ENABLED` nếu chưa cấu hình inbox này; smoke test phải submit cùng idempotency key hai
lần, xác nhận đúng một email và tuyệt đối không có tên/email/note của prospect trong nội dung.

## Release and rollback

Image backend phải pin theo digest/commit SHA. Migration theo expand/contract; deploy schema tương thích
trước application và không rollback qua migration phá vỡ compatibility. Canary revision rủi ro cao,
kiểm tra liveness/readiness, tenant isolation, payment webhook và một analysis job có cost ledger trước
khi chuyển toàn bộ traffic. Giữ Pages deployment và Cloud Run revision trước cho rollback.
