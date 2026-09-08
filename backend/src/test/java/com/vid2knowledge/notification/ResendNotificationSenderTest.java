package com.vid2knowledge.notification;

import com.sun.net.httpserver.HttpServer;
import com.vid2knowledge.config.NotificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendNotificationSenderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTheDocumentedRequestWithProviderIdempotency() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/emails", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"email-provider-id\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String providerId = new ResendNotificationSender(properties()).send(new OutboundEmail(
                "student@example.com", "Xin chào", "<p>Nội dung</p>", "Nội dung", "notification-id"
        ));

        assertThat(providerId).isEqualTo("email-provider-id");
        assertThat(authorization.get()).isEqualTo("Bearer re_test");
        assertThat(idempotency.get()).isEqualTo("notification-id");
        assertThat(body.get()).contains("student@example.com", "Xin chào", "<p>Nội dung</p>");
    }

    @Test
    void treatsIdempotencyPayloadConflictAsPermanent() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/emails", exchange -> {
            byte[] response = "{\"name\":\"invalid_idempotent_request\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> new ResendNotificationSender(properties()).send(new OutboundEmail(
                "student@example.com", "Subject", "<p>Body</p>", "Body", "notification-id"
        ))).isInstanceOf(NotificationDeliveryException.class)
                .extracting(failure -> ((NotificationDeliveryException) failure).retryable())
                .isEqualTo(false);
    }

    private NotificationProperties properties() {
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.UTF_8));
        return new NotificationProperties(
                true, "re_test", "Vid2Knowledge <hello@example.com>",
                URI.create("http://localhost:" + server.getAddress().getPort()),
                URI.create("https://app.example.com"), "founder@example.com", key, 25, 3, Duration.ofMinutes(2)
        );
    }
}
