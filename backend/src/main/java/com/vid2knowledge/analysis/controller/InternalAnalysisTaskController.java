package com.vid2knowledge.analysis.controller;

import com.vid2knowledge.analysis.application.AnalysisWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/tasks/analysis")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalAnalysisTaskController {

    private final AnalysisWorker worker;

    public InternalAnalysisTaskController(AnalysisWorker worker) {
        this.worker = worker;
    }

    @PostMapping("/{jobId}")
    public ResponseEntity<Void> process(@PathVariable UUID jobId) {
        String workerId = "cloud-task-" + UUID.randomUUID();
        return switch (worker.process(jobId, workerId)) {
            case RETRY_SCHEDULED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            case NOT_CLAIMED, COMPLETED, FAILED -> ResponseEntity.noContent().build();
        };
    }
}
