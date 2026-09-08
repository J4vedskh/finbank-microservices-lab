package com.banking.payment.messaging;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
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
    private final long maxRetryDelayMs;
    private final int maxAttempts;
    private final Clock clock;

    @Autowired
    public PaymentOutboxPublisher(
            PaymentOutboxRepository paymentOutboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${payment.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
            @Value("${payment.outbox.retry-delay-ms:5000}") long retryDelayMs,
            @Value("${payment.outbox.max-retry-delay-ms:300000}") long maxRetryDelayMs,
            @Value("${payment.outbox.max-attempts:5}") int maxAttempts
    ) {
        this(
                paymentOutboxRepository,
                kafkaTemplate,
                sendTimeoutMs,
                retryDelayMs,
                maxRetryDelayMs,
                maxAttempts,
                Clock.systemUTC()
        );
    }

    PaymentOutboxPublisher(
            PaymentOutboxRepository paymentOutboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            long sendTimeoutMs,
            long retryDelayMs,
            long maxRetryDelayMs,
            int maxAttempts,
            Clock clock
    ) {
        validatePolicy(sendTimeoutMs, retryDelayMs, maxRetryDelayMs, maxAttempts);
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
        this.retryDelayMs = retryDelayMs;
        this.maxRetryDelayMs = maxRetryDelayMs;
        this.maxAttempts = maxAttempts;
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public boolean publishNext() {
        Instant attemptedAt = clock.instant();
        Optional<PaymentOutboxEvent> pending = paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        PaymentOutboxStatus.PENDING,
                        attemptedAt
                );
        if (pending.isEmpty()) {
            return false;
        }

        PaymentOutboxEvent event = pending.get();
        if (event.getAttemptCount() >= maxAttempts) {
            event.markExhaustedWithoutAttempt(clock.instant());
            event.getPayment().setStatus("PUBLISH_EXHAUSTED");
            return true;
        }

        Throwable failure = sendAndAwaitAcknowledgement(event);
        if (failure == null) {
            event.markPublished(clock.instant());
            event.getPayment().setStatus("PUBLISHED");
        } else {
            recordFailure(event, failure, clock.instant());
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

    private void recordFailure(
            PaymentOutboxEvent event,
            Throwable failure,
            Instant failedAt
    ) {
        int failedAttempt = event.getAttemptCount() + 1;
        String errorType = errorType(failure);
        if (failedAttempt >= maxAttempts) {
            event.markExhausted(failedAt, errorType);
            event.getPayment().setStatus("PUBLISH_EXHAUSTED");
            return;
        }

        event.scheduleRetry(
                failedAt.plusMillis(retryDelayFor(failedAttempt)),
                errorType
        );
        event.getPayment().setStatus("PENDING_RETRY");
    }

    private long retryDelayFor(int failedAttempt) {
        long delay = Math.min(retryDelayMs, maxRetryDelayMs);
        int doublings = Math.min(Math.max(0, failedAttempt - 1), 63);
        for (int index = 0; index < doublings && delay < maxRetryDelayMs; index++) {
            if (delay > maxRetryDelayMs / 2) {
                return maxRetryDelayMs;
            }
            delay *= 2;
        }
        return Math.min(delay, maxRetryDelayMs);
    }

    private void validatePolicy(
            long sendTimeoutMs,
            long retryDelayMs,
            long maxRetryDelayMs,
            int maxAttempts
    ) {
        if (sendTimeoutMs <= 0) {
            throw new IllegalArgumentException("send timeout must be positive");
        }
        if (retryDelayMs <= 0) {
            throw new IllegalArgumentException("retry delay must be positive");
        }
        if (maxRetryDelayMs <= 0) {
            throw new IllegalArgumentException("maximum retry delay must be positive");
        }
        if (maxRetryDelayMs < retryDelayMs) {
            throw new IllegalArgumentException(
                    "maximum retry delay must be greater than or equal to retry delay"
            );
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maximum attempts must be positive");
        }
    }
}
