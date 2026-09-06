package com.vid2knowledge;

import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.common.outbox.OutboxStore;
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
        "task-queue.mode=INLINE",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.com",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.com/jwks"
})
class ProductionContextIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

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

    @Test
    void bootsThePersistenceEnabledApplicationWithAllCriticalStores() {
        assertThat(analysisJobs).isNotNull();
        assertThat(usageQuota).isNotNull();
        assertThat(outbox).isNotNull();
    }
}
