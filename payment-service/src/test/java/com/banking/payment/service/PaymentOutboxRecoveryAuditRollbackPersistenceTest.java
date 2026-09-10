package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(PaymentOutboxRecoveryTransaction.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxRecoveryAuditRollbackPersistenceTest {
    private static final String COMMAND_KEY_HASH = "d".repeat(64);

    @Autowired
    private PaymentOutboxRecoveryTransaction recoveryTransaction;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @MockBean
    private PaymentOutboxRecoveryAuditRepository auditRepository;

    @Test
    void requeueExhausted_auditInsertFailureRollsBackOutboxAndPaymentChanges() {
        PaymentOutboxEvent event = persistExhaustedEvent();
        Long eventId = event.getId();
        Long paymentId = event.getPayment().getId();
        Instant exhaustedAt = event.getExhaustedAt();
        int attemptCount = event.getAttemptCount();
        when(auditRepository.findByRecoveryKeyHash(COMMAND_KEY_HASH))
                .thenReturn(Optional.empty());
        when(auditRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("audit write failed"));

        assertThatThrownBy(() -> recoveryTransaction.requeueExhausted(
                eventId,
                COMMAND_KEY_HASH,
                "portfolio-operator",
                "Re-arm after broker connectivity was restored"
        )).isInstanceOf(DataIntegrityViolationException.class);

        PaymentOutboxEvent unchanged = paymentOutboxRepository.findById(eventId).orElseThrow();
        assertThat(unchanged.getAttemptCount()).isEqualTo(attemptCount);
        assertThat(unchanged.getExhaustedAt()).isEqualTo(exhaustedAt);
        assertThat(unchanged.getLastError()).isEqualTo("TimeoutException");
        assertThat(paymentRepository.findById(paymentId))
                .hasValueSatisfying(payment ->
                        assertThat(payment.getStatus()).isEqualTo("PUBLISH_EXHAUSTED"));
    }

    private PaymentOutboxEvent persistExhaustedEvent() {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash("e".repeat(64));
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("PUBLISH_EXHAUSTED");
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        Instant createdAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                savedPayment,
                "payments",
                savedPayment.getId().toString(),
                savedPayment.getId() + "|1|2|750.00",
                createdAt
        );
        event.scheduleRetry(createdAt.plusSeconds(5), "Failure1");
        event.scheduleRetry(createdAt.plusSeconds(10), "Failure2");
        event.scheduleRetry(createdAt.plusSeconds(15), "Failure3");
        event.scheduleRetry(createdAt.plusSeconds(20), "Failure4");
        event.markExhausted(createdAt.plusSeconds(25), "TimeoutException");
        return paymentOutboxRepository.saveAndFlush(event);
    }
}
