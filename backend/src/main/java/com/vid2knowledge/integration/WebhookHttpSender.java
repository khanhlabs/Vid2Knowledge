package com.vid2knowledge.integration;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public interface WebhookHttpSender {
    int send(URI target, UUID deliveryId, String eventType, Instant timestamp, String signature, String payload);
}
