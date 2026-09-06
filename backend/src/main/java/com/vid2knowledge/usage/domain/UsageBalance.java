package com.vid2knowledge.usage.domain;

public record UsageBalance(long allowance, long committed, long reserved) {

    public UsageBalance {
        if (allowance < 0 || committed < 0 || reserved < 0) {
            throw new IllegalArgumentException("Usage values cannot be negative");
        }
        if (committed + reserved > allowance) {
            throw new IllegalArgumentException("Committed and reserved usage exceed allowance");
        }
    }

    public long available() {
        return allowance - committed - reserved;
    }

    public UsageBalance reserve(long units) {
        requirePositive(units);
        if (units > available()) {
            throw new QuotaExceededException(units, available());
        }
        return new UsageBalance(allowance, committed, Math.addExact(reserved, units));
    }

    public UsageBalance commit(long reservedUnits, long actualUnits) {
        requirePositive(reservedUnits);
        if (reservedUnits > reserved) {
            throw new IllegalArgumentException("Cannot commit more units than currently reserved");
        }
        if (actualUnits < 0 || actualUnits > reservedUnits) {
            throw new IllegalArgumentException("Actual units must be between zero and the reservation");
        }
        return new UsageBalance(
                allowance,
                Math.addExact(committed, actualUnits),
                reserved - reservedUnits
        );
    }

    public UsageBalance release(long units) {
        requirePositive(units);
        if (units > reserved) {
            throw new IllegalArgumentException("Cannot release more units than currently reserved");
        }
        return new UsageBalance(allowance, committed, reserved - units);
    }

    private static void requirePositive(long units) {
        if (units <= 0) {
            throw new IllegalArgumentException("Usage units must be positive");
        }
    }
}
