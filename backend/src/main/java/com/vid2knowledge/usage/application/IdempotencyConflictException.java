package com.vid2knowledge.usage.application;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("The idempotency key was already used with different request parameters");
    }
}
