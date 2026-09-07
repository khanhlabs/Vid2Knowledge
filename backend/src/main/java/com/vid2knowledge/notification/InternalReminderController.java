package com.vid2knowledge.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tasks/reviews/schedule")
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class InternalReminderController {
    private final ReminderSchedulingService reminders;

    public InternalReminderController(ReminderSchedulingService reminders) {
        this.reminders = reminders;
    }

    @PostMapping
    public ReminderSchedulingService.ScheduleResult schedule() {
        return reminders.schedule();
    }
}
