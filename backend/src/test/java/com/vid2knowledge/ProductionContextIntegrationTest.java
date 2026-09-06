package com.vid2knowledge;

import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.common.outbox.OutboxStore;
import com.vid2knowledge.notification.JdbcNotificationQueue;
import com.vid2knowledge.notification.NotificationDispatcher;
import com.vid2knowledge.notification.NotificationQueue;
import com.vid2knowledge.usage.application.UsageQuota;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("prod")
@SpringBootTest(properties = {
        "features.auth-enabled=false",
        "features.outbox-poller-enabled=false",
        "payos.enabled=false",
        "notifications.enabled=true",
        "notifications.api-key=re_test",
        "notifications.from=Vid2Knowledge <hello@example.com>",
        "notifications.frontend-base-url=https://app.example.com",
        "notifications.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
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

    @Test
    void bootsThePersistenceEnabledApplicationWithAllCriticalStores() {
        assertThat(analysisJobs).isNotNull();
        assertThat(usageQuota).isNotNull();
        assertThat(outbox).isNotNull();
        assertThat(notificationQueue).isInstanceOf(JdbcNotificationQueue.class);
        assertThat(notificationDispatcher).isNotNull();
    }
}
