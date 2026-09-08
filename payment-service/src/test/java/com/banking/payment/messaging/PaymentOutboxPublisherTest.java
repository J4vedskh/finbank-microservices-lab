package com.banking.payment.messaging;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxPublisherTest {
    private static final Instant NOW = Instant.parse("2026-09-08T05:30:00Z");

    @Mock
    private PaymentOutboxRepository paymentOutboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishNext_noDueEvent_returnsFalseWithoutKafkaInteraction() {
        PaymentOutboxPublisher publisher = publisher();
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        any(PaymentOutboxStatus.class),
                        any(Instant.class)
                )).thenReturn(Optional.empty());

        boolean processed = publisher.publishNext();

        assertThat(processed).isFalse();
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publishNext_acknowledgedEvent_marksOutboxAndPaymentPublished() throws Exception {
        PaymentOutboxEvent event = pendingEvent();
        PaymentOutboxPublisher publisher = publisher();
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenReturn(CompletableFuture.completedFuture(null));

        boolean processed = publisher.publishNext();

        assertThat(processed).isTrue();
        verify(kafkaTemplate).send("payments", "42", "42|1|2|750.00");
        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PUBLISHED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getPayment().getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void publishNext_failedAcknowledgement_schedulesSafeRetryMetadata() {
        PaymentOutboxEvent event = pendingEvent();
        PaymentOutboxPublisher publisher = publisher();
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("secret broker detail"));
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenReturn(failed);

        boolean processed = publisher.publishNext();

        assertThat(processed).isTrue();
        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("IllegalStateException");
        assertThat(event.getLastError()).doesNotContain("secret broker detail");
        assertThat(event.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
    }

    @Test
    void publishNext_repeatedFailure_usesExponentialDelay() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.minusSeconds(2), "FirstFailure");
        event.scheduleRetry(NOW.minusSeconds(1), "SecondFailure");
        PaymentOutboxPublisher publisher = publisher();
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenReturn(failed);

        publisher.publishNext();

        assertThat(event.getAttemptCount()).isEqualTo(3);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
    }

    @Test
    void publishNext_exponentialDelay_isCapped() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.minusSeconds(3), "Failure1");
        event.scheduleRetry(NOW.minusSeconds(2), "Failure2");
        event.scheduleRetry(NOW.minusSeconds(1), "Failure3");
        PaymentOutboxPublisher publisher = publisher(5_000, 12_000, 6);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenReturn(failed);

        publisher.publishNext();

        assertThat(event.getAttemptCount()).isEqualTo(4);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(12));
    }

    @Test
    void publishNext_lastAllowedFailure_marksPublicationExhausted() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.minusSeconds(2), "Failure1");
        event.scheduleRetry(NOW.minusSeconds(1), "Failure2");
        PaymentOutboxPublisher publisher = publisher(5_000, 60_000, 3);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event), Optional.empty());
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenReturn(failed);

        assertThat(publisher.publishNext()).isTrue();
        assertThat(publisher.publishNext()).isFalse();

        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(3);
        assertThat(event.getExhaustedAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("IllegalStateException");
        assertThat(event.getPayment().getStatus()).isEqualTo("PUBLISH_EXHAUSTED");
        verify(kafkaTemplate, times(1)).send("payments", "42", "42|1|2|750.00");
    }

    @Test
    void publishNext_alreadyAtConfiguredLimit_exhaustsWithoutAnotherSend() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.minusSeconds(3), "Failure1");
        event.scheduleRetry(NOW.minusSeconds(2), "Failure2");
        event.scheduleRetry(NOW.minusSeconds(1), "Failure3");
        PaymentOutboxPublisher publisher = publisher(5_000, 60_000, 3);
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));

        assertThat(publisher.publishNext()).isTrue();

        assertThat(event.getAttemptCount()).isEqualTo(3);
        assertThat(event.getExhaustedAt()).isEqualTo(NOW);
        assertThat(event.getLastError()).isEqualTo("Failure3");
        assertThat(event.getPayment().getStatus()).isEqualTo("PUBLISH_EXHAUSTED");
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishNext_timeout_schedulesRetry() throws Exception {
        PaymentOutboxEvent event = pendingEvent();
        PaymentOutboxPublisher publisher = publisher();
        CompletableFuture<SendResult<String, String>> pending =
                org.mockito.Mockito.mock(CompletableFuture.class);
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenReturn(pending);
        when(pending.get(1_000, TimeUnit.MILLISECONDS))
                .thenThrow(new TimeoutException("broker timeout detail"));

        boolean processed = publisher.publishNext();

        assertThat(processed).isTrue();
        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("TimeoutException");
        assertThat(event.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
    }

    @Test
    void publishNext_synchronousSendFailure_schedulesRetry() {
        PaymentOutboxEvent event = pendingEvent();
        PaymentOutboxPublisher publisher = publisher();
        when(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenThrow(new IllegalStateException("broker unavailable"));

        boolean processed = publisher.publishNext();

        assertThat(processed).isTrue();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("IllegalStateException");
        assertThat(event.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
    }

    @ParameterizedTest
    @MethodSource("invalidPolicies")
    void constructor_invalidRetryPolicy_failsFast(
            long sendTimeoutMs,
            long retryDelayMs,
            long maxRetryDelayMs,
            int maxAttempts
    ) {
        assertThatThrownBy(() -> new PaymentOutboxPublisher(
                paymentOutboxRepository,
                kafkaTemplate,
                sendTimeoutMs,
                retryDelayMs,
                maxRetryDelayMs,
                maxAttempts,
                Clock.fixed(NOW, ZoneOffset.UTC)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private PaymentOutboxPublisher publisher() {
        return publisher(5_000, 60_000, 5);
    }

    private PaymentOutboxPublisher publisher(
            long retryDelayMs,
            long maxRetryDelayMs,
            int maxAttempts
    ) {
        return new PaymentOutboxPublisher(
                paymentOutboxRepository,
                kafkaTemplate,
                1_000,
                retryDelayMs,
                maxRetryDelayMs,
                maxAttempts,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PaymentOutboxEvent pendingEvent() {
        Payment payment = new Payment();
        payment.setId(42L);
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        return new PaymentOutboxEvent(
                payment,
                "payments",
                "42",
                "42|1|2|750.00",
                NOW.minusSeconds(1)
        );
    }

    private static Stream<Arguments> invalidPolicies() {
        return Stream.of(
                Arguments.of(0L, 5_000L, 60_000L, 5),
                Arguments.of(1_000L, 0L, 60_000L, 5),
                Arguments.of(1_000L, 5_000L, 0L, 5),
                Arguments.of(1_000L, 5_000L, 60_000L, 0),
                Arguments.of(1_000L, 5_000L, 4_999L, 5)
        );
    }
}
