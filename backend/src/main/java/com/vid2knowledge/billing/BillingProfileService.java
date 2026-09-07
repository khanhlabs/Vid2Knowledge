package com.vid2knowledge.billing;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class BillingProfileService {
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public BillingProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Profile profile(UUID organizationId) {
        return find(organizationId).stream().findFirst().orElse(null);
    }

    @Transactional
    public Profile update(CurrentActor actor, UpdateProfile request) {
        if (request == null || request.buyerType() == null) {
            throw rejected("Buyer type is required");
        }
        String legalName = text(request.legalName(), "Legal name", 240);
        String address = text(request.billingAddress(), "Billing address", 500);
        String email = text(request.billingEmail(), "Billing email", 320).toLowerCase(Locale.ROOT);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw rejected("Billing email is invalid");
        }
        String country = text(request.countryCode(), "Country code", 2).toUpperCase(Locale.ROOT);
        if (!"VN".equals(country)) {
            throw rejected("Only Vietnamese billing profiles are supported in the initial market");
        }
        String taxIdentifier = nullableText(request.taxIdentifier(), 32);
        if (taxIdentifier != null) taxIdentifier = taxIdentifier.replace(" ", "");
        if (request.buyerType() == BuyerType.BUSINESS
                && (taxIdentifier == null || !taxIdentifier.matches("[0-9]{10}(?:-[0-9]{3})?"))) {
            throw rejected("Vietnamese business tax identifier must contain 10 digits or 10 digits plus a 3-digit branch code");
        }
        if (request.buyerType() == BuyerType.INDIVIDUAL) taxIdentifier = null;

        jdbc.queryForObject(
                "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                String.class, actor.organizationId() + "|billing-profile"
        );
        List<Profile> existing = find(actor.organizationId());
        long currentVersion = existing.isEmpty() ? 0 : existing.getFirst().version();
        if (request.expectedVersion() != currentVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Billing profile changed; reload before saving");
        }
        long nextVersion = currentVersion + 1;
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO organization_billing_profiles(
                    organization_id, buyer_type, legal_name, tax_identifier, billing_address,
                    billing_email, country_code, invoice_requested, version, updated_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id) DO UPDATE SET
                    buyer_type = EXCLUDED.buyer_type, legal_name = EXCLUDED.legal_name,
                    tax_identifier = EXCLUDED.tax_identifier, billing_address = EXCLUDED.billing_address,
                    billing_email = EXCLUDED.billing_email, country_code = EXCLUDED.country_code,
                    invoice_requested = EXCLUDED.invoice_requested, version = EXCLUDED.version,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """,
                actor.organizationId(), request.buyerType().name(), legalName, taxIdentifier, address,
                email, country, request.invoiceRequested(), nextVersion, actor.userId(),
                Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type, resource_id,
                    metadata_json, correlation_id, created_at
                ) VALUES (?, ?, ?, 'BILLING_PROFILE_UPDATED', 'ORGANIZATION', ?,
                    jsonb_build_object('version', ?, 'invoiceRequested', ?), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), actor.organizationId(),
                nextVersion, request.invoiceRequested(), "billing-profile-" + UuidV7Generator.generate(),
                Timestamp.from(now)
        );
        return find(actor.organizationId()).getFirst();
    }

    private List<Profile> find(UUID organizationId) {
        return jdbc.query(
                """
                SELECT buyer_type, legal_name, tax_identifier, billing_address, billing_email,
                       country_code, invoice_requested, version, updated_at
                FROM organization_billing_profiles WHERE organization_id = ?
                """,
                (result, row) -> new Profile(
                        BuyerType.valueOf(result.getString("buyer_type")), result.getString("legal_name"),
                        result.getString("tax_identifier"), result.getString("billing_address"),
                        result.getString("billing_email"), result.getString("country_code"),
                        result.getBoolean("invoice_requested"), result.getLong("version"),
                        result.getTimestamp("updated_at").toInstant()
                ), organizationId
        );
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw rejected(label + " is required and must be at most " + max + " characters");
        }
        return value.trim();
    }

    private static String nullableText(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw rejected("Tax identifier is too long");
        return value.trim();
    }

    private static ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public enum BuyerType { BUSINESS, INDIVIDUAL }

    public record UpdateProfile(
            BuyerType buyerType, String legalName, String taxIdentifier, String billingAddress,
            String billingEmail, String countryCode, boolean invoiceRequested, long expectedVersion
    ) { }

    public record Profile(
            BuyerType buyerType, String legalName, String taxIdentifier, String billingAddress,
            String billingEmail, String countryCode, boolean invoiceRequested, long version, Instant updatedAt
    ) { }
}
