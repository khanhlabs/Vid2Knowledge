package com.vid2knowledge.privacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tasks/privacy/deletions")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalPrivacyController {
    private final PrivacyService privacy;

    public InternalPrivacyController(PrivacyService privacy) {
        this.privacy = privacy;
    }

    @PostMapping
    public PrivacyService.ProcessingResult process() {
        return privacy.processDueDeletions();
    }
}
