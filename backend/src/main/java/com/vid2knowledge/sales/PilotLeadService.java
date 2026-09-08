package com.vid2knowledge.sales;

import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.LegalProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PilotLeadService {
    public static final String CONTACT_CONSENT_VERSION = "pilot-contact-v1-draft";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CAMPAIGN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{15,159}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String contactConsentVersion;
    private final Clock clock = Clock.systemUTC();

    @Autowired
    public PilotLeadService(JdbcTemplate jdbc, TransactionTemplate transactions, LegalProperties legal) {
        this(jdbc, transactions, legal.privacyVersion());
    }

    public PilotLeadService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(jdbc, transactions, CONTACT_CONSENT_VERSION);
    }

    private PilotLeadService(JdbcTemplate jdbc, TransactionTemplate transactions, String contactConsentVersion) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.contactConsentVersion = contactConsentVersion;
    }

    public Submission submit(LeadRequest request, String idempotencyKey, String submitterEvidence) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency key is invalid");
        }
        LeadInput input = normalize(request);
        String fingerprint = RequestFingerprint.sha256(String.join("|",
                input.contactName(), input.workEmail(), input.organizationName(), input.buyerRole().name(),
                input.monthlyVideoMinutes().name(), input.learnerCount().name(), input.primaryGoal().name(),
                input.note() == null ? "" : input.note(), input.acquisitionSource().name(),
                input.acquisitionCampaign() == null ? "" : input.acquisitionCampaign()
        ));
        return transactions.execute(status -> {
            jdbc.queryForObject(
                    "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                    String.class, "pilot-lead|" + idempotencyKey
            );
            List<Submission> existing = jdbc.query(
                    "SELECT id, priority, created_at, request_fingerprint FROM pilot_leads WHERE idempotency_key = ?",
                    (result, row) -> {
                        if (!fingerprint.equals(result.getString("request_fingerprint"))) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was reused");
                        }
                        return new Submission(
                                result.getObject("id", UUID.class), result.getString("priority"),
                                result.getTimestamp("created_at").toInstant()
                        );
                    }, idempotencyKey
            );
            if (!existing.isEmpty()) return existing.getFirst();
            Instant now = clock.instant();
            int fitScore = score(input);
            String priority = fitScore >= 70 ? "HOT" : fitScore >= 45 ? "WARM" : "NURTURE";
            UUID id = UuidV7Generator.generate();
            jdbc.update(
                    """
                    INSERT INTO pilot_leads(
                        id, idempotency_key, request_fingerprint, contact_name, work_email,
                        normalized_email, organization_name, buyer_role, monthly_video_minutes,
                        learner_count, primary_goal, note, acquisition_source, acquisition_campaign,
                        contact_consent_version, contact_consent_at, submitter_hash,
                        fit_score, priority, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, idempotencyKey, fingerprint, input.contactName(), input.workEmail(),
                    input.workEmail().toLowerCase(Locale.ROOT), input.organizationName(), input.buyerRole().name(),
                    input.monthlyVideoMinutes().name(), input.learnerCount().name(), input.primaryGoal().name(),
                    input.note(), input.acquisitionSource().name(), input.acquisitionCampaign(),
                    contactConsentVersion, Timestamp.from(now), RequestFingerprint.sha256(submitterEvidence),
                    fitScore, priority, Timestamp.from(now), Timestamp.from(now)
            );
            jdbc.update(
                    """
                    INSERT INTO pilot_lead_events(id, pilot_lead_id, to_status, occurred_at)
                    VALUES (?, ?, 'NEW', ?)
                    """,
                    UuidV7Generator.generate(), id, Timestamp.from(now)
            );
            return new Submission(id, priority, now);
        });
    }

    public List<LeadView> queue(Status selectedStatus) {
        String statusClause = selectedStatus == null
                ? "AND status NOT IN ('WON', 'LOST')\n"
                : "AND status = ?\n";
        Object[] arguments = selectedStatus == null ? new Object[0] : new Object[]{selectedStatus.name()};
        return jdbc.query(
                """
                SELECT id, contact_name, work_email, organization_name, buyer_role,
                       monthly_video_minutes, learner_count, primary_goal, note,
                       acquisition_source, acquisition_campaign, fit_score, priority, status,
                       organization_id, lost_reason, contact_consent_version,
                       contact_consent_at, created_at, updated_at
                FROM pilot_leads
                WHERE redacted_at IS NULL
                """ + statusClause + """
                ORDER BY CASE priority WHEN 'HOT' THEN 1 WHEN 'WARM' THEN 2 ELSE 3 END,
                         created_at LIMIT 500
                """,
                PilotLeadService::mapLead, arguments
        );
    }

    public List<FunnelRow> funnel() {
        return jdbc.query(
                """
                SELECT l.acquisition_source, l.acquisition_campaign,
                       count(*) AS leads,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM pilot_lead_events e
                           WHERE e.pilot_lead_id = l.id AND e.to_status = 'CONTACTED'
                       )) AS contacted,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM pilot_lead_events e
                           WHERE e.pilot_lead_id = l.id AND e.to_status = 'QUALIFIED'
                       )) AS qualified,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM pilot_lead_events e
                           WHERE e.pilot_lead_id = l.id AND e.to_status = 'PROPOSAL'
                       )) AS proposals,
                       count(*) FILTER (WHERE l.status = 'WON') AS won,
                       count(*) FILTER (WHERE l.status = 'LOST') AS lost,
                       sum(CASE WHEN l.status = 'WON' THEN
                           COALESCE((SELECT sum(p.amount_vnd) FROM payments p
                                     WHERE p.organization_id = l.organization_id), 0)
                           - COALESCE((SELECT sum(r.amount_vnd) FROM refund_requests r
                                       WHERE r.organization_id = l.organization_id AND r.state = 'SUCCEEDED'), 0)
                           ELSE 0 END) AS net_revenue_vnd
                FROM pilot_leads l
                GROUP BY l.acquisition_source, l.acquisition_campaign
                ORDER BY net_revenue_vnd DESC, leads DESC
                """,
                (result, row) -> new FunnelRow(
                        result.getString("acquisition_source"), result.getString("acquisition_campaign"),
                        result.getLong("leads"), result.getLong("contacted"), result.getLong("qualified"),
                        result.getLong("proposals"), result.getLong("won"), result.getLong("lost"),
                        result.getLong("net_revenue_vnd")
                )
        );
    }

    public LeadView update(UUID leadId, StatusUpdate update, String actorSubject) {
        String lostReason = cleanOptional(update.lostReason(), 500);
        if (update.status() == Status.LOST && (lostReason == null || lostReason.length() < 5)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lost reason must be at least 5 characters");
        }
        if (update.status() != Status.LOST && lostReason != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lost reason only applies to LOST leads");
        }
        if (update.status() == Status.WON && update.organizationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Won leads must be linked to an organization");
        }
        if (update.status() != Status.WON && update.organizationId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization only applies to WON leads");
        }
        transactions.executeWithoutResult(transaction -> {
            List<String> current = jdbc.queryForList(
                    "SELECT status FROM pilot_leads WHERE id = ? FOR UPDATE", String.class, leadId
            );
            if (current.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pilot lead not found");
            Status from = Status.valueOf(current.getFirst());
            if (from == update.status()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pilot lead already has this status");
            }
            if (!allowedTransition(from, update.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pilot lead status transition is not allowed");
            }
            Instant now = clock.instant();
            jdbc.update(
                    """
                    UPDATE pilot_leads SET status = ?, organization_id = ?, lost_reason = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    update.status().name(), update.organizationId(), lostReason, Timestamp.from(now), leadId
            );
            jdbc.update(
                    """
                    INSERT INTO pilot_lead_events(
                        id, pilot_lead_id, from_status, to_status, actor_subject_hash, occurred_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UuidV7Generator.generate(), leadId, from.name(), update.status().name(),
                    RequestFingerprint.sha256(actorSubject == null ? "internal" : actorSubject), Timestamp.from(now)
            );
        });
        return jdbc.query(
                """
                SELECT id, contact_name, work_email, organization_name, buyer_role,
                       monthly_video_minutes, learner_count, primary_goal, note,
                       acquisition_source, acquisition_campaign, fit_score, priority, status,
                       organization_id, lost_reason, contact_consent_version,
                       contact_consent_at, created_at, updated_at
                FROM pilot_leads WHERE id = ?
                """,
                PilotLeadService::mapLead, leadId
        ).getFirst();
    }

    private static LeadView mapLead(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new LeadView(
                result.getObject("id", UUID.class), result.getString("contact_name"),
                result.getString("work_email"), result.getString("organization_name"),
                result.getString("buyer_role"), result.getString("monthly_video_minutes"),
                result.getString("learner_count"), result.getString("primary_goal"), result.getString("note"),
                result.getString("acquisition_source"), result.getString("acquisition_campaign"),
                result.getInt("fit_score"), result.getString("priority"), result.getString("status"),
                result.getObject("organization_id", UUID.class), result.getString("lost_reason"),
                result.getString("contact_consent_version"),
                result.getTimestamp("contact_consent_at").toInstant(), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()
        );
    }

    private static LeadInput normalize(LeadRequest request) {
        if (!request.contactConsent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact consent is required");
        }
        String email = text(request.workEmail(), "Work email", 320);
        if (!EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Work email is invalid");
        }
        String campaign = cleanOptional(request.acquisitionCampaign(), 80);
        if (campaign != null && !CAMPAIGN.matcher(campaign).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Acquisition campaign is invalid");
        }
        if (request.buyerRole() == null || request.monthlyVideoMinutes() == null
                || request.learnerCount() == null || request.primaryGoal() == null
                || request.acquisitionSource() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lead qualification fields are required");
        }
        return new LeadInput(
                text(request.contactName(), "Contact name", 160), email,
                text(request.organizationName(), "Organization name", 240), request.buyerRole(),
                request.monthlyVideoMinutes(), request.learnerCount(), request.primaryGoal(),
                cleanOptional(request.note(), 1000), request.acquisitionSource(), campaign
        );
    }

    private static int score(LeadInput input) {
        int score = switch (input.buyerRole()) {
            case OWNER, TRAINING_MANAGER -> 25;
            case OPERATIONS -> 18;
            case INSTRUCTOR -> 12;
            case OTHER -> 5;
        };
        score += switch (input.monthlyVideoMinutes()) {
            case UNDER_100 -> 5;
            case BETWEEN_100_299 -> 15;
            case BETWEEN_300_599 -> 25;
            case BETWEEN_600_1499 -> 30;
            case OVER_1500 -> 25;
        };
        score += switch (input.learnerCount()) {
            case UNDER_50 -> 5;
            case BETWEEN_50_199 -> 12;
            case BETWEEN_200_499 -> 20;
            case BETWEEN_500_999 -> 25;
            case OVER_1000 -> 25;
        };
        score += input.primaryGoal() == Goal.OTHER ? 5 : 15;
        return Math.min(100, score);
    }

    private static boolean allowedTransition(Status from, Status to) {
        return switch (from) {
            case NEW -> to == Status.CONTACTED || to == Status.LOST;
            case CONTACTED -> to == Status.QUALIFIED || to == Status.LOST;
            case QUALIFIED -> to == Status.PROPOSAL || to == Status.LOST;
            case PROPOSAL -> to == Status.WON || to == Status.LOST;
            case WON, LOST -> false;
        };
    }

    private static String text(String value, String name, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required and too long");
        }
        return clean;
    }

    private static String cleanOptional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        if (clean.length() > maximum) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Value is too long");
        return clean;
    }

    public enum BuyerRole { OWNER, TRAINING_MANAGER, INSTRUCTOR, OPERATIONS, OTHER }
    public enum Minutes { UNDER_100, BETWEEN_100_299, BETWEEN_300_599, BETWEEN_600_1499, OVER_1500 }
    public enum Learners { UNDER_50, BETWEEN_50_199, BETWEEN_200_499, BETWEEN_500_999, OVER_1000 }
    public enum Goal { SAVE_AUTHORING_TIME, IMPROVE_COMPLETION, PROVE_LEARNING, SCALE_COHORTS, OTHER }
    public enum Source { DIRECT, SAMPLE_COURSE, FOUNDER_OUTREACH, PARTNER, REFERRAL }
    public enum Status { NEW, CONTACTED, QUALIFIED, PROPOSAL, WON, LOST }

    public record LeadRequest(
            String contactName, String workEmail, String organizationName, BuyerRole buyerRole,
            Minutes monthlyVideoMinutes, Learners learnerCount, Goal primaryGoal, String note,
            Source acquisitionSource, String acquisitionCampaign, boolean contactConsent
    ) { }
    private record LeadInput(
            String contactName, String workEmail, String organizationName, BuyerRole buyerRole,
            Minutes monthlyVideoMinutes, Learners learnerCount, Goal primaryGoal, String note,
            Source acquisitionSource, String acquisitionCampaign
    ) { }
    public record Submission(UUID id, String priority, Instant receivedAt) { }
    public record StatusUpdate(Status status, UUID organizationId, String lostReason) { }
    public record LeadView(
            UUID id, String contactName, String workEmail, String organizationName, String buyerRole,
            String monthlyVideoMinutes, String learnerCount, String primaryGoal, String note,
            String acquisitionSource, String acquisitionCampaign, int fitScore, String priority,
            String status, UUID organizationId, String lostReason, String contactConsentVersion,
            Instant contactConsentAt, Instant createdAt, Instant updatedAt
    ) { }
    public record FunnelRow(
            String acquisitionSource, String acquisitionCampaign, long leads, long contacted,
            long qualified, long proposals, long won, long lost, long netRevenueVnd
    ) { }
}
