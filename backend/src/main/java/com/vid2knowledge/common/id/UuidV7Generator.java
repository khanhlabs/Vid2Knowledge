package com.vid2knowledge.common.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class UuidV7Generator {

    private static final long UNIX_MILLIS_MASK = 0xFFFFFFFFFFFFL;
    private static final long RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL;
    private static final long VARIANT_2 = 0x8000000000000000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7Generator() {
    }

    public static UUID generate() {
        return generate(Clock.systemUTC(), RANDOM);
    }

    static UUID generate(Clock clock, SecureRandom random) {
        long timestamp = clock.millis() & UNIX_MILLIS_MASK;
        long randomA = random.nextInt(1 << 12);
        long randomB = random.nextLong() & RAND_B_MASK;

        long mostSignificantBits = (timestamp << 16) | 0x7000L | randomA;
        long leastSignificantBits = VARIANT_2 | randomB;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
