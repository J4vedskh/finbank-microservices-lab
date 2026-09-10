package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxRecoveryTransactionTest {
    private static final Instant NOW = Instant.parse("2026-09-10T05:30:00Z");
    private static final String COMMAND_KEY_HASH = "a".repeat(64);
    private static final String ACTOR = "portfolio-operator";
    private static final String REASON = "Re-arm after broker connectivity was restored";

    @Mock
    private PaymentOutboxRepository paymentOutboxRepository;

    @Mock
    private PaymentOutboxRecoveryAuditRepository auditRepository;

    @Test
    void requeueExhausted_rearmsEventAndAppendsPreviousFailureSnapshot() {
        PaymentOutboxEvent event = exhaustedEvent();
        String topic = event.getTopic();
        String eventKey = event.getEventKey();
        String payload = event.getPayload();
        Instant previousExhaustedAt = event.getExhaustedAt();
        int previousAttemptCount = event.getAttemptCount();
        String previousLastError = event.getLastError();
        when(paymentOutboxRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(event));
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty());
        when(auditRepository.saveAndFlush(any(PaymentOutboxRecoveryAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentOutboxRecoveryAudit audit = recoveryTransaction()
                .requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON);

        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getExhaustedAt()).isNull();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).isEqualTo(previousLastError);
        assertThat(event.getTopic()).isEqualTo(topic);
        assertThat(event.getEventKey()).isEqualTo(eventKey);
        assertThat(event.getPayload()).isEqualTo(payload);
        assertThat(event.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
        assertThat(audit.getOutboxEvent()).isSameAs(event);
        assertThat(audit.getRecoveryKeyHash()).isEqualTo(COMMAND_KEY_HASH);
        assertThat(audit.getActor()).isEqualTo(ACTOR);
        assertThat(audit.getReason()).isEqualTo(REASON);
        assertThat(audit.getRequeuedAt()).isEqualTo(NOW);
        assertThat(audit.getPreviousExhaustedAt()).isEqualTo(previousExhaustedAt);
        assertThat(audit.getPreviousAttemptCount()).isEqualTo(previousAttemptCount);
        assertThat(audit.getPreviousLastError()).isEqualTo(previousLastError);
        verify(paymentOutboxRepository).flush();
        verify(auditRepository).saveAndFlush(audit);
    }

    @Test
    void requeueExhausted_exactReplayFoundAfterLockReturnsAuditWithoutResettingEvent() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.plusSeconds(30), "RetryFailure");
        int attempts = event.getAttemptCount();
        Instant nextAttemptAt = event.getNextAttemptAt();
        PaymentOutboxRecoveryAudit existing = auditFor(event);
        when(paymentOutboxRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(event));
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.of(existing));

        PaymentOutboxRecoveryAudit result = recoveryTransaction()
                .requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON);

        assertThat(result).isSameAs(existing);
        assertThat(event.getAttemptCount()).isEqualTo(attempts);
        assertThat(event.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    void requeueExhausted_reusedKeyForDifferentRequestIsRejectedAfterLock() {
        PaymentOutboxEvent event = exhaustedEvent();
        PaymentOutboxRecoveryAudit existing = auditFor(event);
        when(paymentOutboxRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(event));
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> recoveryTransaction()
                .requeueExhausted(8L, COMMAND_KEY_HASH, ACTOR, REASON))
                .isInstanceOf(PaymentOutboxRecoveryCommandConflictException.class);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    void requeueExhausted_missingEventIsRejected() {
        when(paymentOutboxRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryTransaction()
                .requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .isInstanceOf(PaymentOutboxEventNotFoundException.class)
                .hasMessage("Payment outbox event 7 was not found");

        verifyNoInteractions(auditRepository);
    }

    @Test
    void requeueExhausted_activeEventIsRejectedWithoutResettingAttempts() {
        PaymentOutboxEvent event = pendingEvent();
        event.scheduleRetry(NOW.plusSeconds(10), "TimeoutException");
        int attempts = event.getAttemptCount();
        when(paymentOutboxRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(event));
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryTransaction()
                .requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);

        assertThat(event.getAttemptCount()).isEqualTo(attempts);
        assertThat(event.getExhaustedAt()).isNull();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    void requeueExhausted_publishedEventIsRejected() {
        PaymentOutboxEvent event = pendingEvent();
        event.markPublished(NOW.minusSeconds(1));
        event.getPayment().setStatus("PUBLISHED");
        when(paymentOutboxRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(event));
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryTransaction()
                .requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);
    }

    @Test
    void requeueExhausted_inconsistentPaymentStateIsRejected() {
        PaymentOutboxEvent event = exhaustedEvent();
        event.getPayment().setStatus("PENDING_RETRY");
        when(paymentOutboxRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(event));
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryTransaction()
                .requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);
    }

    private PaymentOutboxRecoveryTransaction recoveryTransaction() {
        return new PaymentOutboxRecoveryTransaction(
                paymentOutboxRepository,
                auditRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PaymentOutboxRecoveryAudit auditFor(PaymentOutboxEvent event) {
        return new PaymentOutboxRecoveryAudit(
                event,
                COMMAND_KEY_HASH,
                ACTOR,
                REASON,
                NOW.minusSeconds(10),
                NOW.minusSeconds(11),
                5,
                "TimeoutException"
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
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                payment,
                "payments",
                "42",
                "42|1|2|750.00",
                NOW.minusSeconds(10)
        );
        ReflectionTestUtils.setField(event, "id", 7L);
        return event;
    }
}
