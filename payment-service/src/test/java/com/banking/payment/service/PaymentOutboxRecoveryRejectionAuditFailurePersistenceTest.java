package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({
        PaymentOutboxRecoveryService.class,
        PaymentOutboxRecoveryTransaction.class,
        PaymentOutboxRecoveryRejectionAuditTransaction.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxRecoveryRejectionAuditFailurePersistenceTest {

    @Autowired
    private PaymentOutboxRecoveryService recoveryService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private PaymentOutboxRecoveryAuditRepository successfulAuditRepository;

    @MockBean
    private PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository;

    @Test
    void auditWriteFailureReturnsUnavailableAndLeavesIneligibleEventUnchanged() {
        PaymentOutboxEvent event = persistActiveEvent();
        Long paymentId = event.getPayment().getId();
        Instant nextAttemptAt = event.getNextAttemptAt();
        when(rejectionAuditRepository.saveAndFlush(any(PaymentOutboxRecoveryRejectionAudit.class)))
                .thenThrow(new DataIntegrityViolationException("database detail"));

        assertThatThrownBy(() -> recoveryService.requeueExhausted(
                event.getId(),
                "audit-failure-command-0001",
                "recovery-operator",
                "Reason that must not appear in the audit failure"
        )).isInstanceOf(PaymentOutboxRecoveryRejectionAuditUnavailableException.class)
                .hasMessage("Recovery rejection audit is unavailable")
                .hasMessageNotContaining("database detail");

        assertThat(paymentOutboxRepository.findById(event.getId()))
                .hasValueSatisfying(unchanged -> {
                    assertThat(unchanged.getAttemptCount()).isZero();
                    assertThat(unchanged.getExhaustedAt()).isNull();
                    assertThat(unchanged.getNextAttemptAt()).isEqualTo(nextAttemptAt);
                });
        assertThat(paymentRepository.findById(paymentId))
                .hasValueSatisfying(payment -> assertThat(payment.getStatus()).isEqualTo("CREATED"));
        assertThat(successfulAuditRepository.count()).isZero();
    }

    private PaymentOutboxEvent persistActiveEvent() {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash("f".repeat(64));
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        Payment savedPayment = paymentRepository.saveAndFlush(payment);
        Instant createdAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        return paymentOutboxRepository.saveAndFlush(new PaymentOutboxEvent(
                savedPayment,
                "payments",
                savedPayment.getId().toString(),
                savedPayment.getId() + "|1|2|750.00",
                createdAt
        ));
    }
}
