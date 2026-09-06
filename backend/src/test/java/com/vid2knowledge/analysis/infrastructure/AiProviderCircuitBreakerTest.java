package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.AiProviderException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiProviderCircuitBreakerTest {

    @Test
    void opensAfterThresholdAndRejectsWithoutCallingProvider() {
        var circuit = new AiProviderCircuitBreaker(
                2, Duration.ofSeconds(30), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );

        circuit.transientFailure();
        assertThatCode(circuit::beforeCall).doesNotThrowAnyException();
        circuit.transientFailure();

        assertThatThrownBy(circuit::beforeCall)
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((AiProviderException) error).retryable()
                ).isTrue());
    }

    @Test
    void successResetsFailureCount() {
        var circuit = new AiProviderCircuitBreaker(
                2, Duration.ofSeconds(30), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );
        circuit.transientFailure();
        circuit.success();
        circuit.transientFailure();

        assertThatCode(circuit::beforeCall).doesNotThrowAnyException();
    }
}
