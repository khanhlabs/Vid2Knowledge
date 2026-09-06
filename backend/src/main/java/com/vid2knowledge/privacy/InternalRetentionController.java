package com.vid2knowledge.privacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tasks/retention/cleanup")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalRetentionController {
    private final RetentionService retention;

    public InternalRetentionController(RetentionService retention) {
        this.retention = retention;
    }

    @PostMapping
    public RetentionService.CleanupResult cleanup() {
        return retention.cleanup();
    }
}
