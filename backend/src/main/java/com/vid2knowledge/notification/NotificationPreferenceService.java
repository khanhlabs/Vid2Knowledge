package com.vid2knowledge.notification;

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
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationPreferenceService {
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public NotificationPreferenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Preferences get(String authSubject) {
        UUID userId = userId(authSubject);
        jdbc.update("INSERT INTO notification_preferences(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        return preferences(userId);
    }

    @Transactional
    public Preferences update(String authSubject, Preferences requested) {
        UUID userId = userId(authSubject);
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO notification_preferences(
                    user_id, product_guidance_enabled, assignment_reminders_enabled,
                    marketing_enabled, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    product_guidance_enabled = EXCLUDED.product_guidance_enabled,
                    assignment_reminders_enabled = EXCLUDED.assignment_reminders_enabled,
                    marketing_enabled = EXCLUDED.marketing_enabled,
                    updated_at = EXCLUDED.updated_at
                """,
                userId, requested.productGuidanceEnabled(), requested.assignmentRemindersEnabled(),
                requested.marketingEnabled(), Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO notification_preference_changes(
                    id, user_id, product_guidance_enabled, assignment_reminders_enabled,
                    marketing_enabled, changed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), userId, requested.productGuidanceEnabled(),
                requested.assignmentRemindersEnabled(), requested.marketingEnabled(), Timestamp.from(now)
        );
        return preferences(userId);
    }

    private UUID userId(String authSubject) {
        return jdbc.query(
                "SELECT id FROM users WHERE auth_subject = ? AND status = 'ACTIVE'",
                (result, row) -> result.getObject("id", UUID.class), authSubject
        ).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Active user not found")
        );
    }

    private Preferences preferences(UUID userId) {
        return jdbc.queryForObject(
                """
                SELECT product_guidance_enabled, assignment_reminders_enabled, marketing_enabled
                FROM notification_preferences WHERE user_id = ?
                """,
                (result, row) -> new Preferences(
                        result.getBoolean("product_guidance_enabled"),
                        result.getBoolean("assignment_reminders_enabled"),
                        result.getBoolean("marketing_enabled")
                ), userId
        );
    }

    public record Preferences(
            boolean productGuidanceEnabled, boolean assignmentRemindersEnabled, boolean marketingEnabled
    ) { }
}
