package com.vid2knowledge.common.outbox;

import com.vid2knowledge.config.TaskQueueProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxConfiguration {

    @Bean
    OutboxDispatcher outboxDispatcher(
            OutboxStore store,
            EventPublisher publisher,
            TaskQueueProperties properties
    ) {
        return new OutboxDispatcher(
                store,
                publisher,
                Clock.systemUTC(),
                properties.dispatchBatchSize(),
                properties.outboxMaxAttempts(),
                Duration.ofMinutes(2)
        );
    }
}
