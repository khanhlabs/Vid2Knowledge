package com.vid2knowledge.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

@Component
@ConditionalOnExpression("${features.persistence-enabled:true} && ${features.outbox-poller-enabled:true}")
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxDispatcher dispatcher;
    private final String workerId = "outbox-" + ManagementFactory.getRuntimeMXBean().getName();

    public OutboxPoller(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${task-queue.dispatch-interval:5s}")
    public void dispatch() {
        var result = dispatcher.dispatch(workerId);
        if (result.claimed() > 0) {
            log.info(
                    "Outbox batch dispatched. claimed={}, published={}, failed={}, deadLettered={}",
                    result.claimed(), result.published(), result.failed(), result.deadLettered()
            );
        }
    }
}
