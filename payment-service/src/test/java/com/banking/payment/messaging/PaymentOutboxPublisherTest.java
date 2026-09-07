package com.banking.payment.messaging;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxPublisherTest {

    @Mock
    private PaymentOutboxRepository paymentOutboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishNext_noDueEvent_returnsFalseWithoutKafkaInteraction() {
        PaymentOutboxPublisher publisher = publisher();
        when(paymentOutboxRepository
                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
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
                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
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
                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        eq(PaymentOutboxStatus.PENDING),
                        any(Instant.class)
                )).thenReturn(Optional.of(event));
        when(kafkaTemplate.send("payments", "42", "42|1|2|750.00"))
                .thenReturn(failed);

        boolean processed = publisher.publishNext();

        assertThat(processed).isTrue();
        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(event.getCreatedAt());
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("IllegalStateException");
        assertThat(event.getLastError()).doesNotContain("secret broker detail");
        assertThat(event.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishNext_timeout_schedulesRetry() throws Exception {
        PaymentOutboxEvent event = pendingEvent();
        PaymentOutboxPublisher publisher = publisher();
        CompletableFuture<SendResult<String, String>> pending =
                org.mockito.Mockito.mock(CompletableFuture.class);
        when(paymentOutboxRepository
                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
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
                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
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

    private PaymentOutboxPublisher publisher() {
        return new PaymentOutboxPublisher(
                paymentOutboxRepository,
                kafkaTemplate,
                1_000,
                5_000
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
                Instant.now().minusSeconds(1)
        );
    }
}
