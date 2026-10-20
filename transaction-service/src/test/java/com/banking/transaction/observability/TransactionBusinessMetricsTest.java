package com.banking.transaction.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TransactionBusinessMetricsTest {

    @Test
    void recordsAcceptedDuplicateEventsWithoutCustomTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransactionBusinessMetrics metrics = new TransactionBusinessMetrics(registry);
        Counter counter = registry.get(TransactionBusinessMetrics.DUPLICATE_EVENTS_METRIC)
                .counter();

        assertThat(counter.count()).isZero();

        metrics.recordDuplicateEvent();

        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTags()).isEmpty();
    }
}
