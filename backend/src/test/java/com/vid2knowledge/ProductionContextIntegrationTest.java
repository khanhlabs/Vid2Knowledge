package com.vid2knowledge;

import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.common.outbox.OutboxStore;
import com.vid2knowledge.notification.JdbcNotificationQueue;
import com.vid2knowledge.notification.NotificationDispatcher;
import com.vid2knowledge.notification.NotificationQueue;
import com.vid2knowledge.usage.application.UsageQuota;
import com.vid2knowledge.integration.ApiKeyService;
import com.vid2knowledge.integration.WebhookDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "features.auth-enabled=false",
        "features.outbox-poller-enabled=false",
        "legal.reviewed=true",
        "legal.terms-url=https://example.com/legal/terms",
        "legal.privacy-url=https://example.com/legal/privacy",
        "legal.acceptable-use-url=https://example.com/legal/acceptable-use",
        "legal.ai-notice-url=https://example.com/legal/ai-notice",
        "payos.enabled=false",
        "notifications.enabled=true",
        "notifications.api-key=re_test",
        "notifications.from=Vid2Knowledge <hello@example.com>",
        "notifications.frontend-base-url=https://app.example.com",
        "notifications.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "integrations.enabled=true",
        "integrations.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "task-queue.mode=INLINE",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.com",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.com/jwks"
})
class ProductionContextIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    AnalysisJobStore analysisJobs;

    @Autowired
    UsageQuota usageQuota;

    @Autowired
    OutboxStore outbox;

    @Autowired
    NotificationQueue notificationQueue;

    @Autowired
    NotificationDispatcher notificationDispatcher;

    @Autowired
    ApiKeyService apiKeyService;

    @Autowired
    WebhookDispatcher webhookDispatcher;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void bootsThePersistenceEnabledApplicationWithAllCriticalStores() {
        assertThat(analysisJobs).isNotNull();
        assertThat(usageQuota).isNotNull();
        assertThat(outbox).isNotNull();
        assertThat(notificationQueue).isInstanceOf(JdbcNotificationQueue.class);
        assertThat(notificationDispatcher).isNotNull();
        assertThat(apiKeyService).isNotNull();
        assertThat(webhookDispatcher).isNotNull();
    }

    @Test
    void businessApiKeyAuthenticatesOnlyItsOwnTenant() throws Exception {
        UUID userId = UuidV7Generator.generate();
        UUID organizationId = UuidV7Generator.generate();
        UUID orderId = UuidV7Generator.generate();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, 'Owner')",
                userId, "integration-owner-" + userId, userId + "@example.com", userId + "@example.com"
        );
        jdbc.update("INSERT INTO organizations(id, name, slug) VALUES (?, 'API tenant', ?)",
                organizationId, "api-" + organizationId.toString().substring(0, 8));
        jdbc.update("INSERT INTO memberships(organization_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')",
                organizationId, userId);
        jdbc.update(
                """
                INSERT INTO billing_orders(
                    id, organization_id, plan_id, order_code, amount_vnd, state, idempotency_key,
                    request_fingerprint, expires_at, paid_at, created_by
                ) VALUES (?, ?, '00000000-0000-7000-8000-000000000401', ?, 7990000, 'PAID', ?, ?, ?, ?, ?)
                """,
                orderId, organizationId, Math.abs(organizationId.getLeastSignificantBits() % 800000000L) + 100000000L,
                "prod-key-" + orderId, "prod-fingerprint-" + orderId, Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now), userId
        );
        jdbc.update(
                """
                INSERT INTO subscriptions(
                    id, organization_id, plan_id, billing_order_id, status, current_period_start, current_period_end
                ) VALUES (?, ?, '00000000-0000-7000-8000-000000000401', ?, 'ACTIVE', ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, orderId, Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.plus(Duration.ofDays(30)))
        );
        var created = apiKeyService.create(
                new CurrentActor(userId, organizationId, CurrentActor.Role.OWNER),
                "E2E key", Set.of("catalog:read"), now.plus(Duration.ofDays(7)), "corr-e2e"
        );

        mockMvc.perform(get("/api/v1/integrations/v1/organizations/{organizationId}/courses", organizationId)
                        .header("Authorization", "Bearer " + created.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/integrations/v1/organizations/{organizationId}/courses", UUID.randomUUID())
                        .header("Authorization", "Bearer " + created.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/v1/organizations/{organizationId}/courses", organizationId)
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }
}
