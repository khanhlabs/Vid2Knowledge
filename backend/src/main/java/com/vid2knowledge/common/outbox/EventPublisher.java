package com.vid2knowledge.common.outbox;

public interface EventPublisher {

    void publish(OutboxEvent event);
}
