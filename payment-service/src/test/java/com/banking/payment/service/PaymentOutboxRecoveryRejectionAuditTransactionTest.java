package com.banking.payment.service;

import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionCode;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxRecoveryRejectionAuditTransactionTest {
    private static final Instant NOW = Instant.parse("2026-09-15T05:30:00Z");

    @Mock
    private PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository;

    @Test
    void recordPersistsOnlyRequestedEventCodeAndServerTime() {
        when(rejectionAuditRepository.saveAndFlush(any(PaymentOutboxRecoveryRejectionAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentOutboxRecoveryRejectionAudit audit = auditTransaction().record(
                7L,
                PaymentOutboxRecoveryRejectionCode.EVENT_NOT_ELIGIBLE
        );

        assertThat(audit.getRequestedEventId()).isEqualTo(7L);
        assertThat(audit.getRejectionCode())
                .isEqualTo(PaymentOutboxRecoveryRejectionCode.EVENT_NOT_ELIGIBLE);
        assertThat(audit.getRejectedAt()).isEqualTo(NOW);
        verify(rejectionAuditRepository).saveAndFlush(audit);
    }

    @Test
    void entitySchemaContainsNoRequestOrIdentityDataFields() {
        assertThat(Arrays.stream(PaymentOutboxRecoveryRejectionAudit.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .toList())
                .containsExactlyInAnyOrder(
                        "id",
                        "requestedEventId",
                        "rejectionCode",
                        "rejectedAt"
                )
                .doesNotContain(
                        "actor",
                        "reason",
                        "recoveryKey",
                        "recoveryKeyHash",
                        "payload",
                        "eventKey",
                        "lastError",
                        "payment",
                        "account"
                );
    }

    @Test
    void recordPersistenceFailureBecomesSafeUnavailableException() {
        DataIntegrityViolationException persistenceFailure =
                new DataIntegrityViolationException("constraint and request detail");
        when(rejectionAuditRepository.saveAndFlush(any(PaymentOutboxRecoveryRejectionAudit.class)))
                .thenThrow(persistenceFailure);

        assertThatThrownBy(() -> auditTransaction().record(
                7L,
                PaymentOutboxRecoveryRejectionCode.EVENT_NOT_FOUND
        )).isInstanceOf(PaymentOutboxRecoveryRejectionAuditUnavailableException.class)
                .hasMessage("Recovery rejection audit is unavailable")
                .hasMessageNotContaining("constraint")
                .hasCause(persistenceFailure);
    }

    private PaymentOutboxRecoveryRejectionAuditTransaction auditTransaction() {
        return new PaymentOutboxRecoveryRejectionAuditTransaction(
                rejectionAuditRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
