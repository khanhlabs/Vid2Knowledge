package com.vid2knowledge.common.outbox;

import com.vid2knowledge.config.TaskQueueProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "task-queue", name = "mode", havingValue = "CLOUD_TASKS")
public class CloudTasksEventPublisher implements EventPublisher {

    private final TaskQueueProperties properties;
    private final GcpMetadataAccessTokenProvider tokens;
    private final RestClient cloudTasks = RestClient.create("https://cloudtasks.googleapis.com/v2");

    public CloudTasksEventPublisher(TaskQueueProperties properties, GcpMetadataAccessTokenProvider tokens) {
        this.properties = properties;
        this.tokens = tokens;
    }

    @Override
    public void publish(OutboxEvent event) {
        if (!"AnalysisRequested".equals(event.eventType())) {
            return;
        }
        String parent = "projects/" + properties.project()
                + "/locations/" + properties.location()
                + "/queues/" + properties.analysisQueue();
        String target = properties.workerBaseUrl().resolve("/internal/tasks/analysis/" + event.aggregateId()).toString();
        Map<String, Object> request = Map.of(
                "task", Map.of(
                        "name", parent + "/tasks/analysis-" + event.id(),
                        "httpRequest", Map.of(
                                "httpMethod", "POST",
                                "url", target,
                                "headers", Map.of(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                                "body", Base64.getEncoder().encodeToString(event.payloadJson().getBytes(StandardCharsets.UTF_8)),
                                "oidcToken", Map.of(
                                        "serviceAccountEmail", properties.serviceAccountEmail(),
                                        "audience", properties.oidcAudience()
                                )
                        )
                )
        );
        try {
            cloudTasks.post()
                    .uri("/" + parent + "/tasks")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict duplicate) {
            // Deterministic task names make replay idempotent; ALREADY_EXISTS is success.
        }
    }
}
