package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T05:30:00Z");

    @Mock
    private PaymentOutboxRepository paymentOutboxRepository;

    @Test
    void requeueExhausted_rearmsStoredEventWithoutChangingItsBusinessData() {
        PaymentOutboxEvent event = exhaustedEvent();
        PaymentOutboxRecoveryService recoveryService = recoveryService();
        String topic = event.getTopic();
        String eventKey = event.getEventKey();
        String payload = event.getPayload();
        String lastError = event.getLastError();
        when(paymentOutboxRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.of(event));

        PaymentOutboxEvent result = recoveryService.requeueExhausted(7L);

        assertThat(result).isSameAs(event);
        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getExhaustedAt()).isNull();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).isEqualTo(lastError);
        assertThat(event.getTopic()).isEqualTo(topic);
        assertThat(event.getEventKey()).isEqualTo(eventKey);
        assertThat(event.getPayload()).isEqualTo(payload);
        assertThat(event.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
        verify(paymentOutboxRepository).findByIdForUpdate(7L);
    }

    @Test
    void requeueExhausted_missingEvent_isRejected() {
        PaymentOutboxRecoveryService recoveryService = recoveryService();
        when(paymentOutboxRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryService.requeueExhausted(7L))
                .isInstanceOf(PaymentOutboxEventNotFoundException.class)
                .hasMessage("Payment outbox event 7 was not found");
    }

    @Test
    void requeueExhausted_activeEvent_isRejectedWithoutResettingAttempts() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.plusSeconds(10), "TimeoutException");
        int attempts = event.getAttemptCount();
        PaymentOutboxRecoveryService recoveryService = recoveryService();
        when(paymentOutboxRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> recoveryService.requeueExhausted(7L))
                .isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);

        assertThat(event.getAttemptCount()).isEqualTo(attempts);
        assertThat(event.getExhaustedAt()).isNull();
    }

    @Test
    void requeueExhausted_publishedEvent_isRejected() {
        PaymentOutboxEvent event = pendingEvent();
        event.markPublished(NOW.minusSeconds(1));
        event.getPayment().setStatus("PUBLISHED");
        PaymentOutboxRecoveryService recoveryService = recoveryService();
        when(paymentOutboxRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> recoveryService.requeueExhausted(7L))
                .isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);
    }

    @Test
    void requeueExhausted_inconsistentPaymentState_isRejected() {
        PaymentOutboxEvent event = exhaustedEvent();
        event.getPayment().setStatus("PENDING_RETRY");
        PaymentOutboxRecoveryService recoveryService = recoveryService();
        when(paymentOutboxRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> recoveryService.requeueExhausted(7L))
                .isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);
    }

    @Test
    void requeueExhausted_secondRecoveryCall_isRejected() {
        PaymentOutboxEvent event = exhaustedEvent();
        PaymentOutboxRecoveryService recoveryService = recoveryService();
        when(paymentOutboxRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.of(event));

        recoveryService.requeueExhausted(7L);

        assertThatThrownBy(() -> recoveryService.requeueExhausted(7L))
                .isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);
        assertThat(event.getAttemptCount()).isZero();
    }

    private PaymentOutboxRecoveryService recoveryService() {
        return new PaymentOutboxRecoveryService(
                paymentOutboxRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PaymentOutboxEvent exhaustedEvent() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.minusSeconds(5), "Failure1");
        event.scheduleRetry(NOW.minusSeconds(4), "Failure2");
        event.scheduleRetry(NOW.minusSeconds(3), "Failure3");
        event.scheduleRetry(NOW.minusSeconds(2), "Failure4");
        event.markExhausted(NOW.minusSeconds(1), "TimeoutException");
        event.getPayment().setStatus("PUBLISH_EXHAUSTED");
        return event;
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
                NOW.minusSeconds(10)
        );
    }
}
