package com.vid2knowledge.common.id;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    @Test
    void generatesRfc9562VersionSevenUuidWithEmbeddedUnixTimestamp() {
        Instant now = Instant.parse("2026-09-06T02:00:00Z");

        var id = UuidV7Generator.generate(Clock.fixed(now, ZoneOffset.UTC), new SecureRandom());
        long embeddedTimestamp = id.getMostSignificantBits() >>> 16;

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(embeddedTimestamp).isEqualTo(now.toEpochMilli());
    }

    @Test
    void generatesDifferentIdentifiersAtTheSameMillisecond() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T02:00:00Z"), ZoneOffset.UTC);

        assertThat(UuidV7Generator.generate(clock, new SecureRandom()))
                .isNotEqualTo(UuidV7Generator.generate(clock, new SecureRandom()));
    }
}
