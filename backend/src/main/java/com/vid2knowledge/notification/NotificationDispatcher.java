package com.vid2knowledge.notification;

import com.vid2knowledge.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationCipher cipher;
    private final NotificationSender sender;
    private final NotificationProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock = Clock.systemUTC();

    public NotificationDispatcher(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            NotificationCipher cipher,
            NotificationSender sender,
            NotificationProperties properties,
            ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cipher = cipher;
        this.sender = sender;
        this.properties = properties;
        this.mapper = mapper;
    }

    public DispatchResult dispatch(String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank() || leaseOwner.length() > 160) {
            throw new IllegalArgumentException("A valid notification lease owner is required");
        }
        List<ClaimedNotification> claimed = transactions.execute(status -> claim(leaseOwner));
        int sent = 0;
        int retried = 0;
        int dead = 0;
        int cancelled = 0;
        for (ClaimedNotification job : claimed == null ? List.<ClaimedNotification>of() : claimed) {
            try {
                JsonNode payload = payload(job);
                String cancellationReason = cancellationReason(job, payload);
                if (cancellationReason != null) {
                    cancel(job, cancellationReason);
                    cancelled++;
                    continue;
                }
                OutboundEmail email = render(job, payload);
                String providerId = sender.send(email);
                markSent(job, providerId);
                sent++;
            } catch (RuntimeException failure) {
                boolean retryable = !(failure instanceof NotificationDeliveryException delivery)
                        || delivery.retryable();
                boolean deadLetter = !retryable || job.attemptCount() >= properties.maxAttempts();
                markFailed(job, failure, deadLetter);
                if (deadLetter) {
                    dead++;
                } else {
                    retried++;
                }
            }
        }
        return new DispatchResult(claimed == null ? 0 : claimed.size(), sent, retried, dead, cancelled);
    }

    private List<ClaimedNotification> claim(String leaseOwner) {
        Instant now = clock.instant();
        return jdbc.query(
                """
                WITH candidates AS (
                    SELECT id FROM notification_jobs
                    WHERE available_at <= ?
                      AND (state = 'PENDING' OR (state = 'PROCESSING' AND lease_expires_at <= ?))
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE notification_jobs n
                SET state = 'PROCESSING', attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_expires_at = ?, updated_at = ?
                FROM candidates c WHERE n.id = c.id
                RETURNING n.id, n.organization_id, n.notification_type, n.recipient_email,
                          n.encrypted_payload, n.attempt_count
                """,
                (result, row) -> new ClaimedNotification(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        result.getString("notification_type"),
                        result.getString("recipient_email"), result.getString("encrypted_payload"),
                        result.getInt("attempt_count"), leaseOwner
                ),
                Timestamp.from(now), Timestamp.from(now), properties.batchSize(), leaseOwner,
                Timestamp.from(now.plus(properties.leaseDuration())), Timestamp.from(now)
        );
    }

    private JsonNode payload(ClaimedNotification job) {
        try {
            return mapper.readTree(cipher.decrypt(job.encryptedPayload(), job.id().toString()));
        } catch (Exception failure) {
            throw new NotificationDeliveryException("Notification payload is invalid", false, failure);
        }
    }

    private OutboundEmail render(ClaimedNotification job, JsonNode payload) {
        String organization = escaped(payload, "organizationName");
        return switch (job.type()) {
            case "INVITATION" -> invitation(job, payload, organization);
            case "PAYMENT_RECEIPT" -> receipt(job, payload, organization);
            case "CANCELLATION_SCHEDULED" -> cancellation(job, payload, organization);
            case "RENEWAL_PAYMENT_REQUIRED" -> renewal(job, payload, organization);
            case "ONBOARDING_WELCOME" -> lifecycle(job, organization, "Workspace đã sẵn sàng",
                    "Bắt đầu bằng một video có quyền sử dụng; checklist trong workspace sẽ dẫn tới outcome đầu tiên.",
                    "/app");
            case "ACTIVATION_NUDGE" -> lifecycle(job, organization, "Hoàn thành outcome đầu tiên",
                    "Tiếp tục checklist từ học liệu, khóa học, cohort đến một học viên hoàn thành.", "/app");
            case "TRIAL_EXPIRING" -> lifecycle(job, organization, "Trial Vid2Knowledge sắp kết thúc",
                    "Chọn gói phù hợp để giữ quota, học liệu và luồng đào tạo không bị gián đoạn.", "/app#billing");
            case "ASSIGNMENT_AVAILABLE" -> assignment(job, payload, organization, false);
            case "ASSIGNMENT_DUE" -> assignment(job, payload, organization, true);
            case "REVIEW_DUE" -> reviewDue(job, payload, organization);
            case "PILOT_LEAD_ALERT" -> pilotLeadAlert(job, payload);
            default -> throw new NotificationDeliveryException("Unsupported notification type", false, null);
        };
    }

    private OutboundEmail pilotLeadAlert(ClaimedNotification job, JsonNode payload) {
        String leadId = escaped(payload, "leadId");
        String priority = escaped(payload, "priority");
        String source = escaped(payload, "acquisitionSource");
        String receivedAt = DATE.format(parseInstant(payload, "receivedAt"));
        String subject = "[" + HtmlUtils.htmlUnescape(priority) + "] Pilot lead mới";
        String detail = "Lead " + leadId + " từ nguồn " + source + " nhận lúc " + receivedAt + " (GMT+7).";
        String html = "<h2>" + HtmlUtils.htmlEscape(subject) + "</h2><p>" + detail
                + "</p><p>Mở sales queue được bảo vệ để xem thông tin liên hệ. Không chuyển tiếp email này.</p>";
        String text = subject + ". " + HtmlUtils.htmlUnescape(detail)
                + " Mở sales queue được bảo vệ để xem thông tin liên hệ. Không chuyển tiếp email này.";
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private OutboundEmail lifecycle(
            ClaimedNotification job, String organization, String subject,
            String message, String relativeLink
    ) {
        String link = properties.frontendBaseUrl().resolve(relativeLink).toString();
        String html = "<h2>" + HtmlUtils.htmlEscape(subject) + "</h2><p>" + organization + ": "
                + HtmlUtils.htmlEscape(message) + "</p><p><a href=\"" + HtmlUtils.htmlEscape(link)
                + "\">Mở Vid2Knowledge</a></p><p>Bạn có thể tắt email hướng dẫn trong cài đặt tài khoản.</p>";
        String text = HtmlUtils.htmlUnescape(organization) + ": " + message + " " + link
                + " Bạn có thể tắt email hướng dẫn trong cài đặt tài khoản.";
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private OutboundEmail assignment(
            ClaimedNotification job, JsonNode payload, String organization, boolean deadlineReminder
    ) {
        String title = escaped(payload, "assignmentTitle");
        String link = learnerLink(job.organizationId());
        String subject;
        String detail;
        if (deadlineReminder) {
            String dueAt = DATE.format(parseInstant(payload, "dueAt"));
            subject = "Sắp đến hạn: " + HtmlUtils.htmlUnescape(title);
            detail = "Bài học “" + title + "” đến hạn lúc " + dueAt + " (GMT+7).";
        } else {
            subject = "Bài học mới: " + HtmlUtils.htmlUnescape(title);
            detail = "Bài học “" + title + "” đã sẵn sàng.";
        }
        String html = "<h2>" + HtmlUtils.htmlEscape(subject) + "</h2><p>" + organization + ": "
                + detail + "</p><p><a href=\"" + HtmlUtils.htmlEscape(link)
                + "\">Mở bài học</a></p><p>Bạn có thể tắt email nhắc học trong cài đặt tài khoản.</p>";
        String text = HtmlUtils.htmlUnescape(organization) + ": " + HtmlUtils.htmlUnescape(detail) + " " + link
                + " Bạn có thể tắt email nhắc học trong cài đặt tài khoản.";
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private OutboundEmail reviewDue(ClaimedNotification job, JsonNode payload, String organization) {
        int dueCards = dueReviewCount(job.organizationId(), parseUuid(payload, "userId"));
        if (dueCards < 1) {
            throw new NotificationDeliveryException("Review reminder count is invalid", false, null);
        }
        String link = learnerLink(job.organizationId());
        String subject = "Có " + dueCards + " thẻ cần ôn hôm nay";
        String detail = "Bạn có " + dueCards + " thẻ đến hạn ôn để duy trì ghi nhớ.";
        String html = "<h2>" + HtmlUtils.htmlEscape(subject) + "</h2><p>" + organization + ": "
                + HtmlUtils.htmlEscape(detail) + "</p><p><a href=\"" + HtmlUtils.htmlEscape(link)
                + "\">Ôn tập ngay</a></p><p>Bạn có thể tắt email nhắc học trong cài đặt tài khoản.</p>";
        String text = HtmlUtils.htmlUnescape(organization) + ": " + detail + " " + link
                + " Bạn có thể tắt email nhắc học trong cài đặt tài khoản.";
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private String cancellationReason(ClaimedNotification job, JsonNode payload) {
        boolean lifecycle = job.type().startsWith("ONBOARDING_") || "ACTIVATION_NUDGE".equals(job.type())
                || "TRIAL_EXPIRING".equals(job.type());
        boolean reminder = "ASSIGNMENT_AVAILABLE".equals(job.type()) || "ASSIGNMENT_DUE".equals(job.type())
                || "REVIEW_DUE".equals(job.type());
        if (!lifecycle && !reminder) return null;
        UUID userId;
        try {
            userId = UUID.fromString(payload.path("userId").asText());
        } catch (RuntimeException failure) {
            throw new NotificationDeliveryException("Notification user ID is invalid", false, failure);
        }
        List<RecipientEligibility> recipients = jdbc.query(
                """
                SELECT COALESCE(n.product_guidance_enabled, TRUE) AS product_guidance_enabled,
                       COALESCE(n.assignment_reminders_enabled, TRUE) AS assignment_reminders_enabled,
                       m.role
                FROM users u JOIN memberships m ON m.user_id = u.id
                JOIN organizations o ON o.id = m.organization_id
                LEFT JOIN notification_preferences n ON n.user_id = u.id
                WHERE u.id = ? AND u.status = 'ACTIVE' AND m.organization_id = ?
                  AND m.status = 'ACTIVE' AND o.status = 'ACTIVE'
                """,
                (result, row) -> new RecipientEligibility(
                        result.getBoolean("product_guidance_enabled"),
                        result.getBoolean("assignment_reminders_enabled"), result.getString("role")
                ),
                userId, job.organizationId()
        );
        if (recipients.isEmpty()) return "RECIPIENT_INELIGIBLE";
        RecipientEligibility eligibility = recipients.getFirst();
        if (lifecycle && !eligibility.productGuidance()) return "PRODUCT_GUIDANCE_DISABLED";
        if (reminder && !eligibility.assignmentReminders()) return "ASSIGNMENT_REMINDERS_DISABLED";
        if (reminder && !"LEARNER".equals(eligibility.role())) return "RECIPIENT_NOT_LEARNER";
        if ("ACTIVATION_NUDGE".equals(job.type())) {
            Boolean activated = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM learner_progress WHERE organization_id = ? AND status = 'COMPLETED')",
                    Boolean.class, job.organizationId()
            );
            if (Boolean.TRUE.equals(activated)) return "ORGANIZATION_ACTIVATED";
        }
        if ("TRIAL_EXPIRING".equals(job.type())) {
            Boolean paid = jdbc.queryForObject(
                    """
                    SELECT EXISTS(SELECT 1 FROM subscriptions WHERE organization_id = ?
                        AND status IN ('ACTIVE', 'PAST_DUE') AND current_period_end > ?)
                    """,
                    Boolean.class, job.organizationId(), Timestamp.from(clock.instant())
            );
            if (Boolean.TRUE.equals(paid)) return "ORGANIZATION_PAID";
        }
        if ("ASSIGNMENT_AVAILABLE".equals(job.type()) || "ASSIGNMENT_DUE".equals(job.type())) {
            UUID assignmentId = parseUuid(payload, "assignmentId");
            Boolean actionable = jdbc.queryForObject(
                    """
                    SELECT EXISTS(SELECT 1 FROM assignments a
                        JOIN assignment_recipients ar ON ar.assignment_id = a.id
                          AND ar.organization_id = a.organization_id
                        JOIN learner_progress lp ON lp.assignment_id = ar.assignment_id AND lp.user_id = ar.user_id
                        WHERE a.organization_id = ? AND a.id = ? AND ar.user_id = ?
                          AND a.state = 'PUBLISHED' AND a.available_at <= ?
                          AND (a.due_at IS NULL OR a.due_at > ?) AND lp.status <> 'COMPLETED')
                    """,
                    Boolean.class, job.organizationId(), assignmentId, userId,
                    Timestamp.from(clock.instant()), Timestamp.from(clock.instant())
            );
            if (!Boolean.TRUE.equals(actionable)) return "ASSIGNMENT_NOT_ACTIONABLE";
        }
        if ("REVIEW_DUE".equals(job.type())) {
            if (dueReviewCount(job.organizationId(), userId) == 0) return "NO_REVIEWS_DUE";
        }
        return null;
    }

    private int dueReviewCount(UUID organizationId, UUID userId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM flashcard_memory_states f
                JOIN assignments a ON a.id = f.assignment_id AND a.organization_id = f.organization_id
                JOIN learner_progress lp ON lp.assignment_id = f.assignment_id AND lp.user_id = f.user_id
                WHERE f.organization_id = ? AND f.user_id = ? AND f.due_at <= ?
                  AND a.state = 'PUBLISHED' AND a.available_at <= ?
                  AND (a.due_at IS NULL OR a.due_at > ?) AND lp.status <> 'COMPLETED'
                """,
                Integer.class, organizationId, userId, Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant())
        );
        return count == null ? 0 : count;
    }

    private String learnerLink(UUID organizationId) {
        return properties.frontendBaseUrl().resolve("/learn/" + organizationId).toString();
    }

    private static UUID parseUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(payload.path(field).asText());
        } catch (RuntimeException failure) {
            throw new NotificationDeliveryException("Notification " + field + " is invalid", false, failure);
        }
    }

    private static Instant parseInstant(JsonNode payload, String field) {
        try {
            return Instant.parse(payload.path(field).asText());
        } catch (RuntimeException failure) {
            throw new NotificationDeliveryException("Notification " + field + " is invalid", false, failure);
        }
    }

    private void cancel(ClaimedNotification job, String reason) {
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE notification_jobs SET state = 'CANCELLED', cancellation_reason = ?, cancelled_at = ?,
                    encrypted_payload = 'REDACTED', lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                WHERE id = ? AND state = 'PROCESSING' AND lease_owner = ?
                """,
                reason, Timestamp.from(now), Timestamp.from(now), job.id(), job.leaseOwner()
        );
        if (changed != 1) throw new IllegalStateException("Notification lease was lost while cancelling");
    }

    private OutboundEmail invitation(ClaimedNotification job, JsonNode payload, String organization) {
        String token = payload.path("token").asText();
        String link = properties.frontendBaseUrl().resolve(
                "/accept-invitation?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)
        ).toString();
        String role = escaped(payload, "role");
        String expires = DATE.format(Instant.parse(payload.path("expiresAt").asText()));
        String subject = "Lời mời tham gia " + HtmlUtils.htmlUnescape(organization);
        String html = "<h2>Bạn được mời vào " + organization + "</h2><p>Vai trò: " + role
                + ". Lời mời hết hạn lúc " + expires + " (GMT+7).</p><p><a href=\""
                + HtmlUtils.htmlEscape(link) + "\">Chấp nhận lời mời</a></p>";
        String text = "Bạn được mời vào " + HtmlUtils.htmlUnescape(organization) + " với vai trò "
                + HtmlUtils.htmlUnescape(role) + ". Hết hạn: " + expires + " (GMT+7). " + link;
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private OutboundEmail receipt(ClaimedNotification job, JsonNode payload, String organization) {
        String invoice = escaped(payload, "invoiceNumber");
        String amount = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"))
                .format(payload.path("amountVnd").asLong());
        String paidAt = DATE.format(Instant.parse(payload.path("paidAt").asText()));
        String subject = "Vid2Knowledge đã nhận thanh toán " + invoice;
        String html = "<h2>Thanh toán thành công</h2><p>" + organization + " đã thanh toán "
                + HtmlUtils.htmlEscape(amount) + ".</p><p>Hóa đơn: " + invoice + " · " + paidAt + " (GMT+7).</p>";
        String text = "Thanh toán thành công cho " + HtmlUtils.htmlUnescape(organization) + ": " + amount
                + ". Hóa đơn " + HtmlUtils.htmlUnescape(invoice) + ", " + paidAt + " (GMT+7).";
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private OutboundEmail cancellation(ClaimedNotification job, JsonNode payload, String organization) {
        String periodEnd = DATE.format(Instant.parse(payload.path("periodEnd").asText()));
        String subject = "Đã lên lịch kết thúc gói Vid2Knowledge";
        String html = "<h2>Đã ghi nhận yêu cầu</h2><p>Gói của " + organization
                + " vẫn dùng được đến " + periodEnd + " (GMT+7) và sẽ không tự gia hạn.</p>";
        String text = "Gói của " + HtmlUtils.htmlUnescape(organization) + " vẫn dùng được đến "
                + periodEnd + " (GMT+7) và sẽ không tự gia hạn.";
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private OutboundEmail renewal(ClaimedNotification job, JsonNode payload, String organization) {
        String invoice = escaped(payload, "invoiceNumber");
        String checkoutUrl = payload.path("checkoutUrl").asText();
        if (!checkoutUrl.startsWith("https://")) {
            throw new NotificationDeliveryException("Renewal checkout URL is invalid", false, null);
        }
        String amount = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"))
                .format(payload.path("amountVnd").asLong());
        String periodEnd = DATE.format(Instant.parse(payload.path("periodEnd").asText()));
        String subject = "Cần thanh toán để tiếp tục Vid2Knowledge";
        String html = "<h2>Gói sắp hết hạn</h2><p>" + organization + " cần thanh toán "
                + HtmlUtils.htmlEscape(amount) + " trước " + periodEnd + " (GMT+7).</p><p><a href=\""
                + HtmlUtils.htmlEscape(checkoutUrl) + "\">Thanh toán " + invoice + "</a></p>";
        String text = "Gói của " + HtmlUtils.htmlUnescape(organization) + " cần thanh toán " + amount
                + " trước " + periodEnd + " (GMT+7). " + invoice + ": " + checkoutUrl;
        return new OutboundEmail(job.recipient(), subject, html, text, job.id().toString());
    }

    private void markSent(ClaimedNotification job, String providerId) {
        int changed = jdbc.update(
                """
                UPDATE notification_jobs
                SET state = 'SENT', provider_message_id = ?, sent_at = ?, encrypted_payload = 'REDACTED',
                    lease_owner = NULL, lease_expires_at = NULL, last_error = NULL, updated_at = ?
                WHERE id = ? AND state = 'PROCESSING' AND lease_owner = ?
                """,
                providerId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), job.id(), job.leaseOwner()
        );
        if (changed != 1) {
            throw new IllegalStateException("Notification lease was lost after provider accepted the email");
        }
    }

    private void markFailed(ClaimedNotification job, RuntimeException failure, boolean deadLetter) {
        Instant now = clock.instant();
        Duration backoff = Duration.ofSeconds(Math.min(3600, 30L << Math.min(job.attemptCount() - 1, 7)));
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        jdbc.update(
                """
                UPDATE notification_jobs
                SET state = ?, available_at = ?, lease_owner = NULL, lease_expires_at = NULL,
                    last_error = ?, dead_lettered_at = ?, updated_at = ?
                WHERE id = ? AND state = 'PROCESSING' AND lease_owner = ?
                """,
                deadLetter ? "DEAD" : "PENDING", Timestamp.from(now.plus(backoff)), message,
                deadLetter ? Timestamp.from(now) : null, Timestamp.from(now), job.id(), job.leaseOwner()
        );
        log.warn("NOTIFICATION_DELIVERY_FAILED notificationId={}, attempt={}, deadLetter={}, errorType={}",
                job.id(), job.attemptCount(), deadLetter, failure.getClass().getSimpleName());
    }

    private static String escaped(JsonNode payload, String field) {
        String value = payload.path(field).asText();
        if (value.isBlank()) {
            throw new NotificationDeliveryException(
                    "Missing notification payload field " + field, false, null
            );
        }
        return HtmlUtils.htmlEscape(value);
    }

    private record ClaimedNotification(
            UUID id, UUID organizationId, String type, String recipient,
            String encryptedPayload, int attemptCount, String leaseOwner
    ) {
    }

    private record RecipientEligibility(boolean productGuidance, boolean assignmentReminders, String role) { }

    public record DispatchResult(int claimed, int sent, int retried, int deadLettered, int cancelled) {
    }
}
