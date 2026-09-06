package com.vid2knowledge.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/tasks/notifications/dispatch")
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class InternalNotificationController {
    private final NotificationDispatcher dispatcher;

    public InternalNotificationController(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public NotificationDispatcher.DispatchResult dispatch() {
        return dispatcher.dispatch("scheduler-" + UUID.randomUUID());
    }
}
