package com.vid2knowledge.usage.domain;

public class QuotaExceededException extends RuntimeException {

    private final long requested;
    private final long available;

    public QuotaExceededException(long requested, long available) {
        super("Requested usage exceeds the available allowance");
        this.requested = requested;
        this.available = available;
    }

    public long requested() {
        return requested;
    }

    public long available() {
        return available;
    }
}
