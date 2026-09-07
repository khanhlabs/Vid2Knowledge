package com.vid2knowledge.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/tasks/source-uploads")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class InternalSourceIngestionController {
    private final SourceIngestionWorker worker;

    public InternalSourceIngestionController(SourceIngestionWorker worker) {
        this.worker = worker;
    }

    @PostMapping("/{uploadId}")
    public ResponseEntity<Void> process(@PathVariable UUID uploadId) {
        var result = worker.process(uploadId, "cloud-task-" + UUID.randomUUID());
        return result == SourceIngestionWorker.WorkResult.RETRY_SCHEDULED
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
                : ResponseEntity.noContent().build();
    }
}
