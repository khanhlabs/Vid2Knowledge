package com.vid2knowledge.common.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/tasks/outbox")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalOutboxController {
    private final OutboxDispatcher dispatcher;

    public InternalOutboxController(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public OutboxDispatcher.DispatchResult dispatch() {
        return dispatcher.dispatch("scheduler-" + UUID.randomUUID());
    }
}
