package com.vid2knowledge.analysis.application;

public class AiProviderException extends RuntimeException {
    private final boolean retryable;

    public AiProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public AiProviderException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    public boolean retryable() {
        return retryable;
    }
}
