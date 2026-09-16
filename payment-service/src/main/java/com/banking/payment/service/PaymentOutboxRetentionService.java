package com.banking.payment.service;

import com.banking.payment.config.PaymentOutboxRetentionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
public class PaymentOutboxRetentionService {
    private final PaymentOutboxRetentionTransaction retentionTransaction;
    private final PaymentOutboxRetentionProperties properties;
    private final Clock clock;

    @Autowired
    public PaymentOutboxRetentionService(
            PaymentOutboxRetentionTransaction retentionTransaction,
            PaymentOutboxRetentionProperties properties
    ) {
        this(retentionTransaction, properties, Clock.systemUTC());
    }

    PaymentOutboxRetentionService(
            PaymentOutboxRetentionTransaction retentionTransaction,
            PaymentOutboxRetentionProperties properties,
            Clock clock
    ) {
        this.retentionTransaction = Objects.requireNonNull(retentionTransaction);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public PaymentOutboxRetentionResult runOnce() {
        Instant now = clock.instant();
        return retentionTransaction.purgeExpired(
                now.minus(properties.publishedRetentionDays(), ChronoUnit.DAYS),
                now.minus(properties.rejectionRetentionDays(), ChronoUnit.DAYS),
                properties.batchSize()
        );
    }
}
