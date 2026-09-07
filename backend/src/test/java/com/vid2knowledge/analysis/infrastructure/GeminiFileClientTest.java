package com.vid2knowledge.analysis.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vid2knowledge.config.GeminiProperties;
import com.vid2knowledge.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiFileClientTest {
    @Test
    void streamsExactObjectThroughResumableProtocolAndDeletesProviderFile() throws Exception {
        byte[] bytes = "video-payload".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicBoolean deleted = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload/v1beta/files", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-goog-upload-command")).isEqualTo("start");
            assertThat(exchange.getRequestHeaders().getFirst("X-goog-upload-header-content-length"))
                    .isEqualTo(Long.toString(bytes.length));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("X-Goog-Upload-URL",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/upload-session");
            respond(exchange, 200, "");
        });
        server.createContext("/upload-session", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, """
                    {"file":{"name":"files/file-1","uri":"gemini://file-1","state":"ACTIVE",
                    "videoMetadata":{"videoDuration":"60.25s"}}}
                    """);
        });
        server.createContext("/v1beta/files/file-1", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleted.set(true);
                respond(exchange, 204, "");
            } else {
                respond(exchange, 200, """
                        {"name":"files/file-1","uri":"gemini://file-1","state":"ACTIVE",
                        "videoMetadata":{"videoDuration":"60.25s"}}
                        """);
            }
        });
        server.start();
        try {
            ObjectStorage storage = mock(ObjectStorage.class);
            when(storage.open("object-key")).thenReturn(new ByteArrayInputStream(bytes));
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta");
            var client = new GeminiFileClient(
                    new GeminiProperties("key", "model", "embedding", base,
                            Duration.ofSeconds(5), Duration.ofSeconds(5)),
                    storage, new ObjectMapper()
            );

            var uploaded = client.uploadAndAwait("object-key", "training.mp4", "video/mp4", bytes.length);
            assertThat(received.get()).isEqualTo(bytes);
            assertThat(uploaded.durationSeconds()).isEqualTo(61);
            assertThat(client.findActive(uploaded.name(), "video/mp4"))
                    .hasValueSatisfying(cached -> assertThat(cached.uri()).isEqualTo("gemini://file-1"));
            client.deleteQuietly(uploaded.name());
            assertThat(deleted).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
