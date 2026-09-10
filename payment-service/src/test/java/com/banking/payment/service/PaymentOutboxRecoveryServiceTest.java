package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxRecoveryServiceTest {
    private static final String COMMAND_KEY = "recovery-command-0001";
    private static final String COMMAND_KEY_HASH =
            "f71582f21be13a45788c16ef3363ed0f0754eb75dfb4569f09bbebec1a4f8416";
    private static final String ACTOR = "portfolio-operator";
    private static final String REASON = "Re-arm after broker connectivity was restored";
    private static final Instant RECOVERED_AT = Instant.parse("2026-09-10T05:30:00Z");

    @Mock
    private PaymentOutboxRecoveryAuditRepository auditRepository;

    @Mock
    private PaymentOutboxRecoveryTransaction recoveryTransaction;

    @Test
    void requeueExhausted_newCommandHashesOpaqueKeyAndDelegatesValidatedRequest() {
        PaymentOutboxRecoveryAudit audit = audit(7L, ACTOR, REASON);
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty());
        when(recoveryTransaction.requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .thenReturn(audit);

        PaymentOutboxRecoveryAudit result = recoveryService()
                .requeueExhausted(7L, COMMAND_KEY, "  " + ACTOR + "  ", "  " + REASON + "  ");

        assertThat(result).isSameAs(audit);
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditRepository).findByRecoveryKeyHash(hashCaptor.capture());
        assertThat(hashCaptor.getValue())
                .isEqualTo(COMMAND_KEY_HASH)
                .doesNotContain(COMMAND_KEY);
        verify(recoveryTransaction).requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON);
    }

    @Test
    void requeueExhausted_exactCommandReplayReturnsStoredAuditWithoutAnotherRecovery() {
        PaymentOutboxRecoveryAudit audit = audit(7L, ACTOR, REASON);
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.of(audit));

        PaymentOutboxRecoveryAudit result = recoveryService()
                .requeueExhausted(7L, COMMAND_KEY, ACTOR, REASON);

        assertThat(result).isSameAs(audit);
        verifyNoInteractions(recoveryTransaction);
    }

    @Test
    void requeueExhausted_reusedKeyForDifferentRequestIsRejectedWithoutLeakingTheKey() {
        PaymentOutboxRecoveryAudit audit = audit(8L, ACTOR, REASON);
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.of(audit));

        assertThatThrownBy(() -> recoveryService()
                .requeueExhausted(7L, COMMAND_KEY, ACTOR, REASON))
                .isInstanceOf(PaymentOutboxRecoveryCommandConflictException.class)
                .hasMessage("Recovery command key is already assigned to another request")
                .hasMessageNotContaining(COMMAND_KEY)
                .hasMessageNotContaining(COMMAND_KEY_HASH);

        verifyNoInteractions(recoveryTransaction);
    }

    @Test
    void requeueExhausted_uniqueKeyRaceResolvesExactCommittedCommandAsReplay() {
        PaymentOutboxRecoveryAudit audit = audit(7L, ACTOR, REASON);
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty(), Optional.of(audit));
        when(recoveryTransaction.requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        PaymentOutboxRecoveryAudit result = recoveryService()
                .requeueExhausted(7L, COMMAND_KEY, ACTOR, REASON);

        assertThat(result).isSameAs(audit);
        verify(auditRepository, times(2)).findByRecoveryKeyHash(COMMAND_KEY_HASH);
    }

    @Test
    void requeueExhausted_uniqueKeyRaceWithDifferentRequestIsAConflict() {
        PaymentOutboxRecoveryAudit audit = audit(8L, ACTOR, REASON);
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty(), Optional.of(audit));
        when(recoveryTransaction.requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> recoveryService()
                .requeueExhausted(7L, COMMAND_KEY, ACTOR, REASON))
                .isInstanceOf(PaymentOutboxRecoveryCommandConflictException.class);
    }

    @Test
    void requeueExhausted_unrelatedIntegrityFailureIsNotHidden() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated constraint");
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty());
        when(recoveryTransaction.requeueExhausted(7L, COMMAND_KEY_HASH, ACTOR, REASON))
                .thenThrow(failure);

        assertThatThrownBy(() -> recoveryService()
                .requeueExhausted(7L, COMMAND_KEY, ACTOR, REASON))
                .isSameAs(failure);
    }

    @Test
    void requeueExhausted_invalidCommandMetadataIsRejectedBeforePersistence() {
        PaymentOutboxRecoveryService service = recoveryService();

        assertThatThrownBy(() -> service.requeueExhausted(0L, COMMAND_KEY, ACTOR, REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requeueExhausted(7L, "too-short", ACTOR, REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requeueExhausted(7L, "recovery key with spaces", ACTOR, REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requeueExhausted(7L, COMMAND_KEY, "   ", REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requeueExhausted(7L, COMMAND_KEY, ACTOR, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        verify(auditRepository, never()).findByRecoveryKeyHash(anyString());
        verifyNoInteractions(recoveryTransaction);
    }

    private PaymentOutboxRecoveryService recoveryService() {
        return new PaymentOutboxRecoveryService(auditRepository, recoveryTransaction);
    }

    private PaymentOutboxRecoveryAudit audit(Long eventId, String actor, String reason) {
        Payment payment = new Payment();
        payment.setId(42L);
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("PUBLISH_EXHAUSTED");
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                payment,
                "payments",
                "42",
                "42|1|2|750.00",
                RECOVERED_AT.minusSeconds(30)
        );
        ReflectionTestUtils.setField(event, "id", eventId);
        return new PaymentOutboxRecoveryAudit(
                event,
                COMMAND_KEY_HASH,
                actor,
                reason,
                RECOVERED_AT,
                RECOVERED_AT.minusSeconds(1),
                5,
                "TimeoutException"
        );
    }
}
