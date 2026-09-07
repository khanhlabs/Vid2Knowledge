package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.AiProviderException;
import com.vid2knowledge.config.GeminiProperties;
import com.vid2knowledge.storage.ObjectStorage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class GeminiFileClient {
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final GeminiProperties properties;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final RestClient api;
    private final HttpClient uploads;

    public GeminiFileClient(GeminiProperties properties, ObjectStorage storage, ObjectMapper mapper) {
        this.properties = properties;
        this.storage = storage;
        this.mapper = mapper;
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.timeout());
        requests.setReadTimeout(properties.timeout());
        this.api = RestClient.builder().requestFactory(requests)
                .defaultHeader("x-goog-api-key", properties.apiKey()).build();
        this.uploads = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public UploadedFile uploadAndAwait(
            String objectKey, String filename, String contentType, long contentLength
    ) {
        URI uploadUrl = begin(filename, contentType, contentLength);
        JsonNode finalized = upload(uploadUrl, objectKey, contentType, contentLength);
        JsonNode file = fileNode(finalized);
        String name = required(file, "name");
        try {
            Instant deadline = Instant.now().plus(properties.fileReadyTimeout());
            while (true) {
                String state = state(file);
                if ("ACTIVE".equals(state)) {
                    return new UploadedFile(name, required(file, "uri"), contentType, durationSeconds(file));
                }
                if ("FAILED".equals(state)) {
                    throw new AiProviderException("Gemini could not process the uploaded video", false);
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new AiProviderException("Gemini file processing timed out", true);
                }
                sleep();
                file = get(name);
            }
        } catch (RuntimeException failure) {
            deleteQuietly(name);
            throw failure;
        }
    }

    public void deleteQuietly(String name) {
        if (name == null || !name.startsWith("files/")) return;
        try {
            api.delete().uri(fileUri(name)).retrieve().toBodilessEntity();
        } catch (RuntimeException ignored) {
            // Gemini files expire automatically; deletion is best-effort data minimization.
        }
    }

    public Optional<UploadedFile> findActive(String name, String contentType) {
        if (name == null || !name.startsWith("files/")) return Optional.empty();
        try {
            JsonNode response = api.get().uri(fileUri(name)).retrieve().body(JsonNode.class);
            if (response == null) return Optional.empty();
            JsonNode file = fileNode(response);
            if (!"ACTIVE".equals(state(file))) return Optional.empty();
            return Optional.of(new UploadedFile(
                    name, required(file, "uri"), contentType, durationSeconds(file)
            ));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return Optional.empty();
            throw providerFailure("read uploaded file", exception);
        } catch (ResourceAccessException exception) {
            throw new AiProviderException("Gemini file status request could not connect", true, exception);
        }
    }

    private URI begin(String filename, String contentType, long contentLength) {
        try {
            var response = api.post().uri(uploadUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Upload-Protocol", "resumable")
                    .header("X-Goog-Upload-Command", "start")
                    .header("X-Goog-Upload-Header-Content-Length", Long.toString(contentLength))
                    .header("X-Goog-Upload-Header-Content-Type", contentType)
                    .body(Map.of("file", Map.of("display_name", filename)))
                    .retrieve().toBodilessEntity();
            String location = response.getHeaders().getFirst("X-Goog-Upload-URL");
            if (location == null || location.isBlank()) {
                throw new AiProviderException("Gemini did not return a resumable upload URL", false);
            }
            return URI.create(location);
        } catch (RestClientResponseException exception) {
            throw providerFailure("start file upload", exception);
        } catch (ResourceAccessException exception) {
            throw new AiProviderException("Gemini file upload could not connect", true, exception);
        }
    }

    private JsonNode upload(URI uploadUrl, String objectKey, String contentType, long contentLength) {
        AtomicBoolean opened = new AtomicBoolean();
        var body = new FixedLengthBodyPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> {
                    if (!opened.compareAndSet(false, true)) {
                        throw new IllegalStateException("Upload body cannot be replayed");
                    }
                    return storage.open(objectKey);
                }), contentLength
        );
        HttpRequest request = HttpRequest.newBuilder(uploadUrl).timeout(properties.fileReadyTimeout())
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .POST(body).build();
        try {
            HttpResponse<String> response = uploads.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                boolean retryable = response.statusCode() == 408 || response.statusCode() == 429
                        || response.statusCode() >= 500;
                throw new AiProviderException("Gemini file upload failed with HTTP " + response.statusCode(), retryable);
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Gemini file upload was interrupted", true, exception);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("Gemini file upload failed", true, exception);
        }
    }

    private JsonNode get(String name) {
        try {
            JsonNode file = api.get().uri(fileUri(name)).retrieve().body(JsonNode.class);
            if (file == null) throw new AiProviderException("Gemini returned an empty file record", false);
            return fileNode(file);
        } catch (RestClientResponseException exception) {
            throw providerFailure("read uploaded file", exception);
        } catch (ResourceAccessException exception) {
            throw new AiProviderException("Gemini file status request could not connect", true, exception);
        }
    }

    private URI uploadUri() {
        URI base = properties.baseUrl();
        return URI.create(base.getScheme() + "://" + base.getAuthority() + "/upload/v1beta/files");
    }

    private URI fileUri(String name) {
        return URI.create(properties.baseUrl().toString().replaceAll("/+$", "") + "/" + name);
    }

    private static JsonNode fileNode(JsonNode response) {
        return response.has("file") ? response.path("file") : response;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new AiProviderException("Gemini file response omitted " + field, false);
        return value;
    }

    private static String state(JsonNode file) {
        JsonNode value = file.path("state");
        return value.isTextual() ? value.asText() : value.path("name").asText("");
    }

    private static long durationSeconds(JsonNode file) {
        String value = file.path("videoMetadata").path("videoDuration").asText("");
        if (!value.endsWith("s")) {
            throw new AiProviderException("Gemini file response omitted video duration", false);
        }
        try {
            return new java.math.BigDecimal(value.substring(0, value.length() - 1))
                    .setScale(0, java.math.RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new AiProviderException("Gemini returned an invalid video duration", false, exception);
        }
    }

    private static AiProviderException providerFailure(String action, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return new AiProviderException("Gemini could not " + action + "; HTTP " + status,
                status == 408 || status == 429 || status >= 500, exception);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Gemini file polling was interrupted", true, exception);
        }
    }

    private record FixedLengthBodyPublisher(HttpRequest.BodyPublisher delegate, long contentLength)
            implements HttpRequest.BodyPublisher {
        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(subscriber);
        }
    }

    public record UploadedFile(String name, String uri, String contentType, long durationSeconds) { }
}
