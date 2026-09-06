package com.vid2knowledge.common.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import com.vid2knowledge.integration.WebhookDispatcher;

import java.util.UUID;

@RestController
@RequestMapping("/internal/tasks/outbox")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalOutboxController {
    private final OutboxDispatcher dispatcher;
    private final ObjectProvider<WebhookDispatcher> webhooks;

    public InternalOutboxController(
            OutboxDispatcher dispatcher, ObjectProvider<WebhookDispatcher> webhooks
    ) {
        this.dispatcher = dispatcher;
        this.webhooks = webhooks;
    }

    @PostMapping
    public MaintenanceDispatchResult dispatch() {
        String worker = "scheduler-" + UUID.randomUUID();
        var outbox = dispatcher.dispatch(worker);
        WebhookDispatcher webhookDispatcher = webhooks.getIfAvailable();
        var webhook = webhookDispatcher == null ? null : webhookDispatcher.dispatch(worker);
        return new MaintenanceDispatchResult(outbox, webhook);
    }

    public record MaintenanceDispatchResult(
            OutboxDispatcher.DispatchResult outbox,
            WebhookDispatcher.DispatchResult webhooks
    ) {}
}
