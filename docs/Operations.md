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

## Data retention operation

Billing reconciliation gọi cùng transaction boundary của maintenance workflow cho retention cleanup;
operator cũng có thể gọi riêng `POST /internal/tasks/retention/cleanup` bằng Cloud Tasks OIDC. Defaults:
raw Gemini output 30 ngày, processed payOS payload/signature 90 ngày, terminal outbox/notification 30 ngày
và invitation terminal/hết hạn 90 ngày. Outbound webhook terminal giữ 30 ngày; integration credential
terminal/version cũ giữ 90 ngày. Idempotency record bị xóa ngay sau `expires_at`.
Source upload `REJECTED|EXPIRED` giữ metadata 30 ngày; object tạm không dựa vào database cleanup mà
dựa vào R2 lifecycle 1 ngày, nên upload bị bỏ dở không tích lũy storage cost.

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

## Release and rollback

Image backend phải pin theo digest/commit SHA. Migration theo expand/contract; deploy schema tương thích
trước application và không rollback qua migration phá vỡ compatibility. Canary revision rủi ro cao,
kiểm tra liveness/readiness, tenant isolation, payment webhook và một analysis job có cost ledger trước
khi chuyển toàn bộ traffic. Giữ Pages deployment và Cloud Run revision trước cho rollback.
