package com.banking.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentBusinessMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-20T05:30:00Z");

    @Mock
    private PaymentOutboxRepository paymentOutboxRepository;

    @Test
    void recordsAutomaticRetriesWithoutCustomTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentBusinessMetrics metrics = metrics(registry, 300_000);

        metrics.recordAutomaticRetry();

        assertThat(registry.get(PaymentBusinessMetrics.RETRIES_METRIC).counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(PaymentBusinessMetrics.RETRIES_METRIC).counter().getId().getTags())
                .isEmpty();
    }

    @Test
    void stuckGaugeCountsPendingUnexhaustedEventsOlderThanThreshold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(paymentOutboxRepository
                .countByStatusAndExhaustedAtIsNullAndCreatedAtBefore(
                        PaymentOutboxStatus.PENDING,
                        NOW.minusMillis(300_000)
                )).thenReturn(3L);

        metrics(registry, 300_000);

        assertThat(registry.get(PaymentBusinessMetrics.STUCK_METRIC).gauge().value())
                .isEqualTo(3.0);
        assertThat(registry.get(PaymentBusinessMetrics.STUCK_METRIC).gauge().getId().getTags())
                .isEmpty();
        verify(paymentOutboxRepository)
                .countByStatusAndExhaustedAtIsNullAndCreatedAtBefore(
                        PaymentOutboxStatus.PENDING,
                        NOW.minusMillis(300_000)
                );
    }

    @Test
    void stuckGaugeReturnsNaNWhenTheRepositoryCannotBeRead() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(paymentOutboxRepository
                .countByStatusAndExhaustedAtIsNullAndCreatedAtBefore(
                        PaymentOutboxStatus.PENDING,
                        NOW.minusMillis(300_000)
                )).thenThrow(new IllegalStateException("database unavailable"));

        metrics(registry, 300_000);

        assertThat(registry.get(PaymentBusinessMetrics.STUCK_METRIC).gauge().value())
                .isNaN();
    }

    @Test
    void rejectsNonPositiveStuckThreshold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> metrics(registry, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stuck outbox threshold must be positive");
    }

    private PaymentBusinessMetrics metrics(
            SimpleMeterRegistry registry,
            long stuckAfterMs
    ) {
        return new PaymentBusinessMetrics(
                paymentOutboxRepository,
                registry,
                stuckAfterMs,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
