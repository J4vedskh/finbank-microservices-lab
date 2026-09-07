package com.banking.payment.messaging;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class PaymentOutboxPublisher {
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutMs;
    private final long retryDelayMs;

    public PaymentOutboxPublisher(
            PaymentOutboxRepository paymentOutboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${payment.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
            @Value("${payment.outbox.retry-delay-ms:5000}") long retryDelayMs
    ) {
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = Math.max(1, sendTimeoutMs);
        this.retryDelayMs = Math.max(1, retryDelayMs);
    }

    @Transactional
    public boolean publishNext() {
        Instant attemptedAt = Instant.now();
        Optional<PaymentOutboxEvent> pending = paymentOutboxRepository
                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        PaymentOutboxStatus.PENDING,
                        attemptedAt
                );
        if (pending.isEmpty()) {
            return false;
        }

        PaymentOutboxEvent event = pending.get();
        Throwable failure = sendAndAwaitAcknowledgement(event);
        if (failure == null) {
            event.markPublished(Instant.now());
            event.getPayment().setStatus("PUBLISHED");
        } else {
            event.scheduleRetry(
                    attemptedAt.plusMillis(retryDelayMs),
                    errorType(failure)
            );
            event.getPayment().setStatus("PENDING_RETRY");
        }
        return true;
    }

    private Throwable sendAndAwaitAcknowledgement(PaymentOutboxEvent event) {
        try {
            kafkaTemplate.send(
                    event.getTopic(),
                    event.getEventKey(),
                    event.getPayload()
            ).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return exception;
        } catch (ExecutionException exception) {
            return exception.getCause() == null ? exception : exception.getCause();
        } catch (TimeoutException | RuntimeException exception) {
            return exception;
        }
    }

    private String errorType(Throwable failure) {
        return failure.getClass().getSimpleName();
    }
}
