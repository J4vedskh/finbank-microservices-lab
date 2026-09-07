package com.banking.payment.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOutboxScheduler {
    private final PaymentOutboxPublisher paymentOutboxPublisher;
    private final int batchSize;

    public PaymentOutboxScheduler(
            PaymentOutboxPublisher paymentOutboxPublisher,
            @Value("${payment.outbox.batch-size:20}") int batchSize
    ) {
        this.paymentOutboxPublisher = paymentOutboxPublisher;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-delay-ms:1000}")
    public void publishBatch() {
        for (int processed = 0; processed < batchSize; processed++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (!paymentOutboxPublisher.publishNext()) {
                return;
            }
        }
    }
}
