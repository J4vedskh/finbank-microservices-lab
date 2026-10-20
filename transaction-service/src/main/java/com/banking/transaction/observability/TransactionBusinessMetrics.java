package com.banking.transaction.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class TransactionBusinessMetrics {

    public static final String DUPLICATE_EVENTS_METRIC =
            "finbank.transaction.duplicate.events";

    private final Counter duplicateEvents;

    public TransactionBusinessMetrics(MeterRegistry meterRegistry) {
        this.duplicateEvents = Counter.builder(DUPLICATE_EVENTS_METRIC)
                .description("Exact payment event replays ignored by the transaction ledger.")
                .register(meterRegistry);
    }

    public void recordDuplicateEvent() {
        duplicateEvents.increment();
    }
}
