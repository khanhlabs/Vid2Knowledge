package com.vid2knowledge.billing;

import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PromotionService {
    private static final Duration MAX_CAMPAIGN_DURATION = Duration.ofDays(730);

    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public PromotionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Campaign> campaigns() {
        return jdbc.query(
                """
                SELECT id, code, name, discount_bps, plan_code_prefix, attribution_channel,
                       partner_reference, starts_at, ends_at, max_redemptions, active,
                       (SELECT count(*) FROM promotion_redemptions r
                        WHERE r.promotion_campaign_id = c.id AND r.state = 'RESERVED'
                          AND r.expires_at > CURRENT_TIMESTAMP) AS reserved,
                       (SELECT count(*) FROM promotion_redemptions r
                        WHERE r.promotion_campaign_id = c.id AND r.state = 'REDEEMED') AS redeemed,
                       (SELECT COALESCE(sum(r.amount_vnd), 0) FROM promotion_redemptions r
                        WHERE r.promotion_campaign_id = c.id AND r.state = 'REDEEMED') AS attributed_revenue_vnd,
                       (SELECT COALESCE(sum(r.discount_vnd), 0) FROM promotion_redemptions r
                        WHERE r.promotion_campaign_id = c.id AND r.state = 'REDEEMED') AS discount_granted_vnd
                FROM promotion_campaigns c ORDER BY created_at DESC, id DESC LIMIT 500
                """,
                (result, row) -> new Campaign(
                        result.getObject("id", UUID.class), result.getString("code"), result.getString("name"),
                        result.getInt("discount_bps"), result.getString("plan_code_prefix"),
                        result.getString("attribution_channel"), result.getString("partner_reference"),
                        result.getTimestamp("starts_at").toInstant(), result.getTimestamp("ends_at").toInstant(),
                        result.getInt("max_redemptions"), result.getBoolean("active"),
                        result.getInt("reserved"), result.getInt("redeemed"),
                        result.getLong("attributed_revenue_vnd"), result.getLong("discount_granted_vnd")
                )
        );
    }

    @Transactional
    public Campaign create(CreateCampaign request, String actorSubject) {
        if (request == null || request.attributionChannel() == null
                || request.startsAt() == null || request.endsAt() == null) {
            throw new IllegalArgumentException("Promotion channel and time window are required");
        }
        String code = normalizeCode(request.code());
        String name = requireText(request.name(), "Campaign name", 160);
        String planPrefix = nullableUpper(request.planCodePrefix(), 40);
        String partnerReference = nullableText(request.partnerReference(), 120);
        int channelCeiling = request.attributionChannel().maxDiscountBps;
        if (request.discountBps() < 1 || request.discountBps() > channelCeiling) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Discount exceeds the " + channelCeiling + " bps margin guardrail for "
                            + request.attributionChannel().name());
        }
        if (request.maxRedemptions() < 1 || request.maxRedemptions() > 1_000_000) {
            throw new IllegalArgumentException("Promotion capacity must be between 1 and 1000000");
        }
        if (!request.endsAt().isAfter(request.startsAt())
                || Duration.between(request.startsAt(), request.endsAt()).compareTo(MAX_CAMPAIGN_DURATION) > 0) {
            throw new IllegalArgumentException("Promotion window must be positive and at most 730 days");
        }
        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        try {
            jdbc.update(
                    """
                    INSERT INTO promotion_campaigns(
                        id, code, name, discount_bps, plan_code_prefix, attribution_channel,
                        partner_reference, starts_at, ends_at, max_redemptions, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, code, name, request.discountBps(), planPrefix, request.attributionChannel().name(),
                    partnerReference, Timestamp.from(request.startsAt()), Timestamp.from(request.endsAt()),
                    request.maxRedemptions(), Timestamp.from(now), Timestamp.from(now)
            );
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Promotion code already exists", duplicate);
        }
        event(id, "CREATED", actorSubject, now);
        return campaigns().stream().filter(campaign -> campaign.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deactivate(UUID campaignId, String actorSubject) {
        Instant now = clock.instant();
        int changed = jdbc.update(
                "UPDATE promotion_campaigns SET active = FALSE, updated_at = ? WHERE id = ? AND active = TRUE",
                Timestamp.from(now), campaignId
        );
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active promotion campaign not found");
        }
        event(campaignId, "DEACTIVATED", actorSubject, now);
    }

    private void event(UUID campaignId, String action, String actorSubject, Instant now) {
        jdbc.update(
                "INSERT INTO promotion_campaign_events(id, promotion_campaign_id, action, actor_subject_hash, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UuidV7Generator.generate(), campaignId, action,
                RequestFingerprint.sha256(actorSubject == null ? "internal" : actorSubject), Timestamp.from(now)
        );
    }

    static String normalizeCode(String value) {
        String code = requireText(value, "Promotion code", 40).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9_-]{2,39}")) {
            throw new IllegalArgumentException("Promotion code must contain 3 to 40 letters, numbers, _ or -");
        }
        return code;
    }

    private static String nullableUpper(String value, int max) {
        String normalized = nullableText(value, max);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String nullableText(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return requireText(value, "Value", max);
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(label + " is required and must be at most " + max + " characters");
        }
        return value.trim();
    }

    public record CreateCampaign(
            String code, String name, int discountBps, String planCodePrefix,
            Channel attributionChannel, String partnerReference,
            Instant startsAt, Instant endsAt, int maxRedemptions
    ) { }

    public enum Channel {
        REFERRAL(1_500), PARTNER(2_000), SALES(2_500), RETENTION(3_000);

        private final int maxDiscountBps;

        Channel(int maxDiscountBps) {
            this.maxDiscountBps = maxDiscountBps;
        }
    }

    public record Campaign(
            UUID id, String code, String name, int discountBps, String planCodePrefix,
            String attributionChannel, String partnerReference, Instant startsAt, Instant endsAt,
            int maxRedemptions, boolean active, int reserved, int redeemed,
            long attributedRevenueVnd, long discountGrantedVnd
    ) { }
}
