package com.vid2knowledge.common.outbox;

import com.vid2knowledge.config.TaskQueueProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import com.vid2knowledge.integration.WebhookFanout;
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
    private final ObjectProvider<WebhookFanout> webhooks;
    private final RestClient cloudTasks = RestClient.create("https://cloudtasks.googleapis.com/v2");

    public CloudTasksEventPublisher(
            TaskQueueProperties properties,
            GcpMetadataAccessTokenProvider tokens,
            ObjectProvider<WebhookFanout> webhooks
    ) {
        this.properties = properties;
        this.tokens = tokens;
        this.webhooks = webhooks;
    }

    @Override
    public void publish(OutboxEvent event) {
        webhooks.ifAvailable(fanout -> fanout.fanout(event));
        String route = switch (event.eventType()) {
            case "AnalysisRequested" -> "/internal/tasks/analysis/" + event.aggregateId();
            case "SourceUploadIngestionRequested" -> "/internal/tasks/source-uploads/" + event.aggregateId();
            default -> null;
        };
        if (route == null) {
            return;
        }
        String parent = "projects/" + properties.project()
                + "/locations/" + properties.location()
                + "/queues/" + properties.analysisQueue();
        String target = properties.workerBaseUrl().resolve(route).toString();
        String taskPrefix = "AnalysisRequested".equals(event.eventType()) ? "analysis-" : "source-ingestion-";
        Map<String, Object> request = Map.of(
                "task", Map.of(
                        "name", parent + "/tasks/" + taskPrefix + event.id(),
                        "dispatchDeadline", "1800s",
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
