package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.analysis.domain.AnalysisWorkItem;
import com.vid2knowledge.analysis.domain.GenerationAccounting;
import com.vid2knowledge.usage.application.UsageQuota;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisCompletionService {

    private final AnalysisJobStore jobs;
    private final UsageQuota quota;

    public AnalysisCompletionService(AnalysisJobStore jobs, UsageQuota quota) {
        this.jobs = jobs;
        this.quota = quota;
    }

    @Transactional
    public void complete(
            AnalysisWorkItem item,
            String workerId,
            GenerationAccounting accounting,
            String contentJson,
            String promptVersion,
            String schemaVersion,
            Instant now
    ) {
        jobs.complete(item, workerId, accounting, contentJson, promptVersion, schemaVersion, now);
        quota.commit(item.job().usageReservationId(), item.billedUnits(), item.correlationId());
    }

    @Transactional
    public void fail(
            AnalysisWorkItem item,
            String workerId,
            GenerationAccounting accounting,
            String code,
            String safeDetail,
            boolean terminal,
            Instant retryAt,
            String promptVersion,
            String schemaVersion,
            Instant now
    ) {
        jobs.fail(
                item, workerId, accounting, code, safeDetail, terminal, retryAt,
                promptVersion, schemaVersion, now
        );
        if (terminal) {
            quota.release(item.job().usageReservationId(), item.correlationId());
        }
    }
}
