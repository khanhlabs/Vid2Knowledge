package com.vid2knowledge.common.outbox;

import com.vid2knowledge.analysis.application.AnalysisWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import com.vid2knowledge.integration.WebhookFanout;
import com.vid2knowledge.storage.SourceIngestionWorker;

@Component
@ConditionalOnExpression("'${task-queue.mode:INLINE}' == 'INLINE' && ${features.persistence-enabled:true}")
public class InlineEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InlineEventPublisher.class);

    private final AnalysisWorker worker;
    private final ObjectProvider<WebhookFanout> webhooks;
    private final ObjectProvider<SourceIngestionWorker> sourceIngestion;

    public InlineEventPublisher(AnalysisWorker worker, ObjectProvider<WebhookFanout> webhooks,
                                ObjectProvider<SourceIngestionWorker> sourceIngestion) {
        this.worker = worker;
        this.webhooks = webhooks;
        this.sourceIngestion = sourceIngestion;
    }

    @Override
    public void publish(OutboxEvent event) {
        webhooks.ifAvailable(fanout -> fanout.fanout(event));
        if ("SourceUploadIngestionRequested".equals(event.eventType())) {
            SourceIngestionWorker ingestion = sourceIngestion.getIfAvailable();
            if (ingestion == null) throw new IllegalStateException("Source ingestion is disabled");
            var result = ingestion.process(event.aggregateId(), "inline-" + event.id());
            if (result == SourceIngestionWorker.WorkResult.RETRY_SCHEDULED) {
                throw new IllegalStateException("Source ingestion requested retry");
            }
            return;
        }
        if (!"AnalysisRequested".equals(event.eventType())) {
            log.debug("No inline consumer registered. eventType={}, eventId={}", event.eventType(), event.id());
            return;
        }
        var result = worker.process(event.aggregateId(), "inline-" + event.id());
        if (result == AnalysisWorker.WorkResult.RETRY_SCHEDULED) {
            throw new IllegalStateException("Analysis provider requested retry");
        }
    }
}
