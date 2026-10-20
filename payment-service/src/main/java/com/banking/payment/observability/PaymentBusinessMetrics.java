package com.banking.payment.observability;

import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentBusinessMetrics {

    public static final String RETRIES_METRIC = "finbank.payment.outbox.retries";
    public static final String STUCK_METRIC = "finbank.payment.outbox.stuck";

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final long stuckAfterMs;
    private final Clock clock;
    private final Counter retries;

    @Autowired
    public PaymentBusinessMetrics(
            PaymentOutboxRepository paymentOutboxRepository,
            MeterRegistry meterRegistry,
            @Value("${payment.observability.stuck-after-ms:300000}") long stuckAfterMs
    ) {
        this(
                paymentOutboxRepository,
                meterRegistry,
                stuckAfterMs,
                Clock.systemUTC()
        );
    }

    PaymentBusinessMetrics(
            PaymentOutboxRepository paymentOutboxRepository,
            MeterRegistry meterRegistry,
            long stuckAfterMs,
            Clock clock
    ) {
        if (stuckAfterMs <= 0) {
            throw new IllegalArgumentException("stuck outbox threshold must be positive");
        }
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.stuckAfterMs = stuckAfterMs;
        this.clock = clock;
        this.retries = Counter.builder(RETRIES_METRIC)
                .description("Automatic payment outbox retries scheduled after a failed publish attempt.")
                .register(meterRegistry);
        Gauge.builder(STUCK_METRIC, this, PaymentBusinessMetrics::stuckOutboxEventCount)
                .description("Pending payment outbox events older than the configured stuck threshold.")
                .register(meterRegistry);
    }

    public void recordAutomaticRetry() {
        retries.increment();
    }

    private double stuckOutboxEventCount() {
        Instant cutoff = clock.instant().minusMillis(stuckAfterMs);
        try {
            return paymentOutboxRepository
                    .countByStatusAndExhaustedAtIsNullAndCreatedAtBefore(
                            PaymentOutboxStatus.PENDING,
                            cutoff
                    );
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }
}
