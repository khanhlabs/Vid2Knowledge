package com.vid2knowledge.legal;

import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.LegalProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class LegalService {
    private final JdbcTemplate jdbc;
    private final LegalProperties legal;

    public LegalService(JdbcTemplate jdbc, LegalProperties legal) {
        this.jdbc = jdbc;
        this.legal = legal;
    }

    public Manifest manifest() {
        return new Manifest(
                legal.policySetVersion(), legal.reviewed(),
                List.of(
                        new Policy("TERMS", legal.termsVersion(), legal.termsUrl().toString()),
                        new Policy("PRIVACY", legal.privacyVersion(), legal.privacyUrl().toString()),
                        new Policy("ACCEPTABLE_USE", legal.acceptableUseVersion(), legal.acceptableUseUrl().toString()),
                        new Policy("AI_NOTICE", legal.aiNoticeVersion(), legal.aiNoticeUrl().toString())
                )
        );
    }

    public Status status(UUID userId) {
        return new Status(hasCurrentAcceptance(userId), manifest());
    }

    public boolean hasCurrentAcceptance(UUID userId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*) FROM legal_acceptances
                WHERE user_id = ? AND policy_set_version = ? AND terms_version = ?
                  AND privacy_version = ? AND acceptable_use_version = ? AND ai_notice_version = ?
                """,
                Long.class, userId, legal.policySetVersion(), legal.termsVersion(),
                legal.privacyVersion(), legal.acceptableUseVersion(), legal.aiNoticeVersion()
        );
        return count != null && count > 0;
    }

    @Transactional
    public Status accept(UUID userId, AcceptanceRequest request, String evidence) {
        Manifest current = manifest();
        if (request == null || !request.confirmed()
                || !current.policySetVersion().equals(request.policySetVersion())
                || !legal.termsVersion().equals(request.termsVersion())
                || !legal.privacyVersion().equals(request.privacyVersion())
                || !legal.acceptableUseVersion().equals(request.acceptableUseVersion())
                || !legal.aiNoticeVersion().equals(request.aiNoticeVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Legal policy versions changed; review them again");
        }
        jdbc.update(
                """
                INSERT INTO legal_acceptances(
                    id, user_id, policy_set_version, terms_version, privacy_version,
                    acceptable_use_version, ai_notice_version, request_evidence_hash, accepted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, policy_set_version, terms_version, privacy_version,
                             acceptable_use_version, ai_notice_version) DO NOTHING
                """,
                UuidV7Generator.generate(), userId, legal.policySetVersion(), legal.termsVersion(),
                legal.privacyVersion(), legal.acceptableUseVersion(), legal.aiNoticeVersion(),
                RequestFingerprint.sha256(evidence), Timestamp.from(Instant.now())
        );
        return status(userId);
    }

    public record Manifest(String policySetVersion, boolean reviewed, List<Policy> policies) { }
    public record Policy(String type, String version, String url) { }
    public record Status(boolean accepted, Manifest manifest) { }
    public record AcceptanceRequest(
            String policySetVersion, String termsVersion, String privacyVersion,
            String acceptableUseVersion, String aiNoticeVersion, boolean confirmed
    ) { }
}
