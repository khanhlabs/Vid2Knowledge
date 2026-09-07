package com.vid2knowledge.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/tasks/notifications/dispatch")
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class InternalNotificationController {
    private static final Logger log = LoggerFactory.getLogger(InternalNotificationController.class);
    private final NotificationDispatcher dispatcher;
    private final ReminderSchedulingService reminders;

    public InternalNotificationController(NotificationDispatcher dispatcher, ReminderSchedulingService reminders) {
        this.dispatcher = dispatcher;
        this.reminders = reminders;
    }

    @PostMapping
    public NotificationDispatcher.DispatchResult dispatch() {
        try {
            reminders.schedule();
        } catch (RuntimeException failure) {
            log.warn("REMINDER_SCHEDULING_FAILED errorType={}", failure.getClass().getSimpleName());
        }
        return dispatcher.dispatch("scheduler-" + UUID.randomUUID());
    }
}
