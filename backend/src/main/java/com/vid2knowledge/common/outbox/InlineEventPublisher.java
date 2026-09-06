package com.vid2knowledge.common.outbox;

import com.vid2knowledge.analysis.application.AnalysisWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${task-queue.mode:INLINE}' == 'INLINE' && ${features.persistence-enabled:true}")
public class InlineEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InlineEventPublisher.class);

    private final AnalysisWorker worker;

    public InlineEventPublisher(AnalysisWorker worker) {
        this.worker = worker;
    }

    @Override
    public void publish(OutboxEvent event) {
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
