package com.vid2knowledge.browser;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.application.port.VideoMetadataProvider;
import com.vid2knowledge.analysis.domain.AnalysisSource;
import com.vid2knowledge.analysis.domain.LearningPackage;
import com.vid2knowledge.analysis.infrastructure.GeminiInteractionClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Test-only server and identities; none of this class is packaged in the application jar. */
@Testcontainers
@ActiveProfiles("prod")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "features.auth-enabled=true",
        "cors.allowed-origins=http://127.0.0.1:4174", "cors.allowed-methods=GET,POST,PUT,PATCH,DELETE,OPTIONS",
        "cors.allowed-headers=*", "cors.allowed-credentials=true",
        "features.outbox-poller-enabled=true", "task-queue.mode=INLINE", "task-queue.dispatch-interval=500ms",
        "task-queue.oidc-audience=browser-test-tasks", "task-queue.service-account-email=tasks@example.invalid",
        "sales-access.oidc-audience=browser-test-sales", "sales-access.service-account-email=sales@example.invalid",
        "legal.require-production-readiness=false", "legal.reviewed=false",
        "notifications.enabled=false", "payos.enabled=false", "integrations.enabled=false",
        "object-storage.enabled=false", "supabase-admin.enabled=false",
        "gemini.api-key=test-not-a-key", "youtube.api-key=test-not-a-key",
        "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN"
})
class BuyerLearnerBrowserIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final HttpServer JWKS = jwksServer();
    private static final String ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID LEARNER = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER + "/jwks");
        properties.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "authenticated");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean GeminiInteractionClient ai;
    @MockitoBean VideoMetadataProvider videoMetadata;

    @AfterAll
    static void stopIdentityProvider() { JWKS.stop(0); }

    @Test
    void buyerCanPublishAssignAndMeasureRealLearnerProgress() throws Exception {
        when(videoMetadata.fetch("abcdefghijk"))
                .thenReturn(new VideoMetadataProvider.VideoMetadata("Browser training", 300, "vi"));
        when(ai.generateLearningPackage(anyString(), any(AnalysisSource.class)))
                .thenReturn(new AiGenerationResult("test", "fixture", "1", 1000, 500, 0, 10, 0,
                        mapper.writeValueAsString(learningPackage())));

        // Verify actual decoder behavior before trusting the browser's signed sessions.
        try (var client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()) {
            for (String token : List.of(token(OWNER, "wrong-audience", Instant.now().plusSeconds(3600)),
                    token(OWNER, "authenticated", Instant.now().minusSeconds(300)))) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(401);
            }
        }
        Path frontend = Path.of("..").resolve("frontend").toAbsolutePath().normalize();
        var command = System.getProperty("os.name").startsWith("Windows")
                ? List.of("cmd.exe", "/c", "npm.cmd", "run", "test:e2e:full")
                : List.of("npm", "run", "test:e2e:full");
        var processBuilder = new ProcessBuilder(command).directory(frontend.toFile())
                .redirectErrorStream(true).redirectOutput(Path.of("target", "browser-journey.log").toFile());
        processBuilder.environment().put("V2K_E2E_BACKEND_URL", "http://127.0.0.1:" + port);
        processBuilder.environment().put("V2K_E2E_OWNER_SESSION", session(OWNER));
        processBuilder.environment().put("V2K_E2E_LEARNER_SESSION", session(LEARNER));
        Process runner = processBuilder.start();
        try {
            assertThat(runner.waitFor(4, TimeUnit.MINUTES)).as("Browser journey completed before timeout").isTrue();
            assertThat(runner.exitValue()).as("Browser journey exit code; see target/browser-journey.log").isZero();
        } finally {
            if (runner.isAlive()) {
                runner.descendants().forEach(ProcessHandle::destroyForcibly);
                runner.destroyForcibly();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assessment_attempts WHERE score_percent = 100", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_packages WHERE publication_state = 'PUBLISHED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(units) FROM usage_ledger WHERE event_type = 'COMMITTED'", Long.class)).isEqualTo(300L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_ledger WHERE shadow_cost_microusd > 0", Integer.class)).isEqualTo(1);
        verify(ai, times(1)).generateLearningPackage(anyString(), any(AnalysisSource.class));
    }

    private String session(UUID subject) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "access_token", token(subject, "authenticated", Instant.now().plusSeconds(3600)),
                "refresh_token", "test-only-refresh", "token_type", "bearer", "expires_in", 3600,
                "expires_at", Instant.now().plusSeconds(3600).getEpochSecond(),
                "user", Map.of("id", subject.toString(), "email", subject + "@example.invalid", "aud", "authenticated",
                        "role", "authenticated", "app_metadata", Map.of(), "user_metadata", Map.of(),
                        "created_at", Instant.now().toString())));
    }

    private static String token(UUID subject, String audience, Instant expires) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject.toString()).audience(audience)
                        .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expires))
                        .claim("email", subject + "@example.invalid").claim("email_verified", true)
                        .claim("name", subject.equals(OWNER) ? "Browser Owner" : "Browser Learner").build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("browser-test-key").generate(); }
        catch (JOSEException failure) { throw new IllegalStateException(failure); }
    }

    private static HttpServer jwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] publicKeys = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, publicKeys.length);
                try (var body = exchange.getResponseBody()) { body.write(publicKeys); }
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static LearningPackage learningPackage() {
        var source = new LearningPackage.SourceReference(0, "Browser fixture evidence");
        return new LearningPackage("learning-package-v3",
                new LearningPackage.Video("https://www.youtube.com/watch?v=abcdefghijk", "abcdefghijk", "Browser training", "vi"),
                new LearningPackage.Summary("Browser overview", List.of(new LearningPackage.Section(
                        "section-one", "Section", source, List.of("Browser training content")))),
                List.of(new LearningPackage.EvidenceItem("takeaway-one", "Browser takeaway", source)),
                IntStream.range(0, 10).mapToObj(i -> new LearningPackage.Flashcard("card-" + i, "Card " + i, "Answer " + i, source)).toList(),
                IntStream.range(0, 5).mapToObj(i -> new LearningPackage.QuizQuestion("quiz-" + i, "Quiz " + i,
                        List.of("A", "B", "C", "D"), 0, "A is correct in the test fixture", source)).toList());
    }
}
