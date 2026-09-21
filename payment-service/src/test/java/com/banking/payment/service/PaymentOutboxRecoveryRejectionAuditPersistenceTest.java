package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionCode;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({
        PaymentOutboxRecoveryService.class,
        PaymentOutboxRecoveryTransaction.class,
        PaymentOutboxRecoveryRejectionAuditTransaction.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxRecoveryRejectionAuditPersistenceTest {
    private static final String ACTOR = "recovery-operator";
    private static final String REASON = "Kafka delivery was checked before requeue";

    @Autowired
    private PaymentOutboxRecoveryService recoveryService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private PaymentOutboxRecoveryAuditRepository successfulAuditRepository;

    @Autowired
    private PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository;

    @AfterEach
    void clearCommittedFixtures() {
        rejectionAuditRepository.deleteAllInBatch();
        successfulAuditRepository.deleteAllInBatch();
        paymentOutboxRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
    }

    @Test
    void missingEventCommitsOneMinimalAuditForEveryRejectedBusinessAttempt() {
        Instant before = Instant.now();

        assertThatThrownBy(() -> recoveryService.requeueExhausted(
                999L,
                "missing-recovery-command-0001",
                ACTOR,
                REASON
        )).isInstanceOf(PaymentOutboxEventNotFoundException.class);
        assertThatThrownBy(() -> recoveryService.requeueExhausted(
                999L,
                "missing-recovery-command-0002",
                ACTOR,
                "A different reason that must not be retained"
        )).isInstanceOf(PaymentOutboxEventNotFoundException.class);

        List<PaymentOutboxRecoveryRejectionAudit> audits = rejectionAuditRepository.findAll();
        Instant after = Instant.now().plus(1, ChronoUnit.MICROS);
        assertThat(audits).hasSize(2).allSatisfy(audit -> {
            assertThat(audit.getRequestedEventId()).isEqualTo(999L);
            assertThat(audit.getRejectionCode())
                    .isEqualTo(PaymentOutboxRecoveryRejectionCode.EVENT_NOT_FOUND);
            assertThat(audit.getRejectedAt()).isBetween(before, after);
        });
        assertThat(successfulAuditRepository.count()).isZero();
    }

    @Test
    void ineligibleEventRemainsUnchangedWhileMinimalRejectionAuditCommits() {
        PaymentOutboxEvent event = persistActiveEvent();
        Long paymentId = event.getPayment().getId();
        Instant nextAttemptAt = event.getNextAttemptAt();

        assertThatThrownBy(() -> recoveryService.requeueExhausted(
                event.getId(),
                "ineligible-command-0001",
                ACTOR,
                REASON
        )).isInstanceOf(PaymentOutboxRecoveryNotAllowedException.class);

        assertThat(rejectionAuditRepository.findAll()).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.getRequestedEventId()).isEqualTo(event.getId());
                    assertThat(audit.getRejectionCode())
                            .isEqualTo(PaymentOutboxRecoveryRejectionCode.EVENT_NOT_ELIGIBLE);
                });
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

    @Test
    void conflictingCommandKeepsSuccessfulRecoveryAndCommitsSeparateRejectionAudit() {
        PaymentOutboxEvent event = persistExhaustedEvent();
        Long paymentId = event.getPayment().getId();
        String commandKey = "conflicting-command-0001";

        PaymentOutboxRecoveryAudit successfulAudit = recoveryService.requeueExhausted(
                event.getId(),
                commandKey,
                ACTOR,
                REASON
        );

        assertThatThrownBy(() -> recoveryService.requeueExhausted(
                event.getId(),
                commandKey,
                ACTOR,
                "Changed reason that must not be retained"
        )).isInstanceOf(PaymentOutboxRecoveryCommandConflictException.class);

        assertThat(successfulAuditRepository.findAll())
                .extracting(PaymentOutboxRecoveryAudit::getId)
                .containsExactly(successfulAudit.getId());
        assertThat(rejectionAuditRepository.findAll()).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.getRequestedEventId()).isEqualTo(event.getId());
                    assertThat(audit.getRejectionCode())
                            .isEqualTo(PaymentOutboxRecoveryRejectionCode.COMMAND_CONFLICT);
                });
        assertThat(paymentOutboxRepository.findById(event.getId()))
                .hasValueSatisfying(recovered -> {
                    assertThat(recovered.getAttemptCount()).isZero();
                    assertThat(recovered.getExhaustedAt()).isNull();
                });
        assertThat(paymentRepository.findById(paymentId))
                .hasValueSatisfying(payment ->
                        assertThat(payment.getStatus()).isEqualTo("PENDING_RETRY"));
    }

    private PaymentOutboxEvent persistActiveEvent() {
        Payment savedPayment = paymentRepository.saveAndFlush(payment("a".repeat(64), "CREATED"));
        Instant createdAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        return paymentOutboxRepository.saveAndFlush(new PaymentOutboxEvent(
                savedPayment,
                "payments",
                savedPayment.getId().toString(),
                savedPayment.getId() + "|1|2|750.00",
                createdAt
        ));
    }

    private PaymentOutboxEvent persistExhaustedEvent() {
        Payment savedPayment = paymentRepository.saveAndFlush(
                payment("b".repeat(64), "PUBLISH_EXHAUSTED")
        );
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

    private Payment payment(String idempotencyKeyHash, String status) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(idempotencyKeyHash);
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus(status);
        return payment;
    }
}
