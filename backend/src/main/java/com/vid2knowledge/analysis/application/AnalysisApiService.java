package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.analysis.domain.AnalysisJob;
import com.vid2knowledge.config.GeminiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisApiService {

    private final JdbcTemplate jdbc;
    private final RequestAnalysisService requests;
    private final AnalysisJobStore jobs;
    private final GeminiProperties gemini;

    public AnalysisApiService(
            JdbcTemplate jdbc,
            RequestAnalysisService requests,
            AnalysisJobStore jobs,
            GeminiProperties gemini
    ) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.jobs = jobs;
        this.gemini = gemini;
    }

    public AnalysisJob submit(
            UUID organizationId,
            UUID sourceId,
            String outputProfileJson,
            String idempotencyKey,
            String correlationId
    ) {
        List<Long> durations = jdbc.query(
                """
                SELECT duration_seconds FROM sources
                WHERE id = ? AND organization_id = ?
                  AND duration_seconds IS NOT NULL AND metadata_verified_at IS NOT NULL
                """,
                (result, row) -> result.getLong("duration_seconds"),
                sourceId,
                organizationId
        );
        long duration = durations.stream().findFirst().orElseThrow(SourceRightsRequiredException::new);
        return requests.request(new RequestAnalysisCommand(
                organizationId,
                sourceId,
                duration,
                outputProfileJson,
                idempotencyKey,
                "GOOGLE_GEMINI",
                gemini.model(),
                correlationId
        ));
    }

    public AnalysisJob get(UUID organizationId, UUID jobId) {
        return jobs.findById(organizationId, jobId).orElseThrow(SourceRightsRequiredException::new);
    }
}
