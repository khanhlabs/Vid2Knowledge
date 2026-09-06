package com.vid2knowledge.notification;

public record OutboundEmail(
        String recipient, String subject, String html, String text, String idempotencyKey
) {
}
