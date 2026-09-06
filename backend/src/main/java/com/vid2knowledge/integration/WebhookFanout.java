package com.vid2knowledge.integration;

import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.outbox.OutboxEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class WebhookFanout {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WebhookFanout(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public int fanout(OutboxEvent event) {
        if (event.organizationId() == null || !WebhookEndpointService.ALLOWED_EVENTS.contains(event.eventType())) {
            return 0;
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", event.id().toString());
        envelope.put("type", event.eventType());
        envelope.put("version", event.eventVersion());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("organizationId", event.organizationId().toString());
        try {
            envelope.set("data", mapper.readTree(event.payloadJson()));
        } catch (Exception invalidPayload) {
            throw new IllegalStateException("Outbox event payload is invalid JSON", invalidPayload);
        }
        var targets = jdbc.query(
                """
                SELECT e.id, e.current_secret_version
                FROM webhook_endpoints e
                WHERE e.organization_id = ? AND e.state = 'ACTIVE' AND ? = ANY(e.event_types)
                  AND EXISTS (
                    SELECT 1 FROM subscriptions s JOIN pricing_plans p ON p.id = s.plan_id
                    WHERE s.organization_id = e.organization_id AND s.status = 'ACTIVE'
                      AND s.current_period_end > ? AND p.code LIKE 'BUSINESS\\_%' ESCAPE '\\'
                  )
                """,
                (result, row) -> new Target(
                        result.getObject("id", UUID.class), result.getInt("current_secret_version")
                ),
                event.organizationId(), event.eventType(), Timestamp.from(java.time.Instant.now())
        );
        int created = 0;
        for (Target target : targets) {
            created += jdbc.update(
                    """
                    INSERT INTO webhook_deliveries(
                        id, organization_id, endpoint_id, outbox_event_id, event_type,
                        event_version, secret_version, payload_json, created_at, available_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    ON CONFLICT (endpoint_id, outbox_event_id) DO NOTHING
                    """,
                    UuidV7Generator.generate(), event.organizationId(), target.endpointId(), event.id(),
                    event.eventType(), event.eventVersion(), target.secretVersion(), envelope.toString(),
                    Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt())
            );
        }
        return created;
    }

    private record Target(UUID endpointId, int secretVersion) {}
}
