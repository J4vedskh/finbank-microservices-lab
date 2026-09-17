package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxDeadLetterHandoff;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionCode;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(PaymentOutboxRetentionTransaction.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxRetentionPersistenceTest {
    private static final Instant CUTOFF = Instant.parse("2026-08-17T05:30:00Z");

    @Autowired
    private PaymentOutboxRetentionTransaction retentionTransaction;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private PaymentOutboxDeadLetterHandoffRepository deadLetterHandoffRepository;

    @Autowired
    private PaymentOutboxRecoveryAuditRepository recoveryAuditRepository;

    @Autowired
    private PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int fixtureSequence;

    @AfterEach
    void clearCommittedFixtures() {
        jdbcTemplate.execute("drop table if exists retention_delete_blocker");
        deadLetterHandoffRepository.deleteAllInBatch();
        rejectionAuditRepository.deleteAllInBatch();
        recoveryAuditRepository.deleteAllInBatch();
        outboxRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
    }

    @Test
    void purgeExpired_removesOnlyStrictlyOldPublishedDataAndKeepsPayments() {
        PaymentOutboxEvent oldPublished = persistPublished(CUTOFF.minusSeconds(1));
        PaymentOutboxDeadLetterHandoff oldHandoff = onlyHandoff(oldPublished);
        Long retainedPaymentId = oldPublished.getPayment().getId();
        PaymentOutboxEvent boundaryPublished = persistPublished(CUTOFF);
        PaymentOutboxDeadLetterHandoff boundaryHandoff = onlyHandoff(boundaryPublished);
        PaymentOutboxEvent recentPublished = persistPublished(CUTOFF.plusSeconds(1));
        PaymentOutboxEvent active = persistPending();
        PaymentOutboxEvent retrying = persistRetrying();
        PaymentOutboxEvent exhausted = persistExhausted();
        PaymentOutboxDeadLetterHandoff exhaustedHandoff = deadLetterHandoffRepository
                .saveAndFlush(new PaymentOutboxDeadLetterHandoff(exhausted));
        PaymentOutboxRecoveryAudit oldRecovery = persistRecoveryAudit(oldPublished);
        PaymentOutboxRecoveryAudit boundaryRecovery = persistRecoveryAudit(boundaryPublished);
        PaymentOutboxRecoveryRejectionAudit oldRejection =
                persistRejection(CUTOFF.minusSeconds(1));
        PaymentOutboxRecoveryRejectionAudit boundaryRejection =
                persistRejection(CUTOFF);
        PaymentOutboxRecoveryRejectionAudit recentRejection =
                persistRejection(CUTOFF.plusSeconds(1));

        PaymentOutboxRetentionResult result = retentionTransaction.purgeExpired(
                CUTOFF,
                CUTOFF,
                100
        );

        assertThat(result).isEqualTo(new PaymentOutboxRetentionResult(1, 1, 1, 1));
        assertThat(outboxRepository.findById(oldPublished.getId())).isEmpty();
        assertThat(deadLetterHandoffRepository.findById(oldHandoff.getId())).isEmpty();
        assertThat(recoveryAuditRepository.findById(oldRecovery.getId())).isEmpty();
        assertThat(paymentRepository.findById(retainedPaymentId)).isPresent();
        assertThat(outboxRepository.findById(boundaryPublished.getId())).isPresent();
        assertThat(outboxRepository.findById(recentPublished.getId())).isPresent();
        assertThat(recoveryAuditRepository.findById(boundaryRecovery.getId())).isPresent();
        assertThat(deadLetterHandoffRepository.findById(boundaryHandoff.getId())).isPresent();
        assertThat(outboxRepository.findById(active.getId())).isPresent();
        assertThat(outboxRepository.findById(retrying.getId())).isPresent();
        assertThat(outboxRepository.findById(exhausted.getId())).isPresent();
        assertThat(deadLetterHandoffRepository.findById(exhaustedHandoff.getId())).isPresent();
        assertThat(rejectionAuditRepository.findById(oldRejection.getId())).isEmpty();
        assertThat(rejectionAuditRepository.findById(boundaryRejection.getId())).isPresent();
        assertThat(rejectionAuditRepository.findById(recentRejection.getId())).isPresent();
    }

    @Test
    void purgeExpired_capsEachCandidateCategoryAndDeletesOldestFirst() {
        PaymentOutboxEvent oldestEvent = persistPublished(CUTOFF.minusSeconds(3));
        PaymentOutboxEvent middleEvent = persistPublished(CUTOFF.minusSeconds(2));
        PaymentOutboxEvent newestEvent = persistPublished(CUTOFF.minusSeconds(1));
        PaymentOutboxRecoveryRejectionAudit oldestRejection =
                persistRejection(CUTOFF.minusSeconds(3));
        PaymentOutboxRecoveryRejectionAudit middleRejection =
                persistRejection(CUTOFF.minusSeconds(2));
        PaymentOutboxRecoveryRejectionAudit newestRejection =
                persistRejection(CUTOFF.minusSeconds(1));

        PaymentOutboxRetentionResult result = retentionTransaction.purgeExpired(
                CUTOFF,
                CUTOFF,
                2
        );

        assertThat(result).isEqualTo(new PaymentOutboxRetentionResult(2, 0, 2, 2));
        assertThat(outboxRepository.findById(oldestEvent.getId())).isEmpty();
        assertThat(outboxRepository.findById(middleEvent.getId())).isEmpty();
        assertThat(outboxRepository.findById(newestEvent.getId())).isPresent();
        assertThat(rejectionAuditRepository.findById(oldestRejection.getId())).isEmpty();
        assertThat(rejectionAuditRepository.findById(middleRejection.getId())).isEmpty();
        assertThat(rejectionAuditRepository.findById(newestRejection.getId())).isPresent();
    }

    @Test
    void purgeExpired_parentDeleteFailureRollsBackSuccessfulAuditDelete() {
        PaymentOutboxEvent oldPublished = persistPublished(CUTOFF.minusSeconds(1));
        PaymentOutboxDeadLetterHandoff deadLetterHandoff = onlyHandoff(oldPublished);
        PaymentOutboxRecoveryAudit recoveryAudit = persistRecoveryAudit(oldPublished);
        jdbcTemplate.execute("""
                create table retention_delete_blocker (
                    id bigint primary key,
                    outbox_event_id bigint not null,
                    constraint fk_retention_delete_blocker
                        foreign key (outbox_event_id) references payment_outbox_event(id)
                )
                """);
        jdbcTemplate.update(
                "insert into retention_delete_blocker (id, outbox_event_id) values (?, ?)",
                1L,
                oldPublished.getId()
        );

        assertThatThrownBy(() -> retentionTransaction.purgeExpired(
                CUTOFF,
                CUTOFF,
                100
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxRepository.findById(oldPublished.getId())).isPresent();
        assertThat(recoveryAuditRepository.findById(recoveryAudit.getId())).isPresent();
        assertThat(deadLetterHandoffRepository.findById(deadLetterHandoff.getId()))
                .isPresent();
    }

    private PaymentOutboxEvent persistPublished(Instant publishedAt) {
        PaymentOutboxEvent event = newEvent("PUBLISH_EXHAUSTED");
        event.markExhausted(
                publishedAt.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS),
                "TimeoutException"
        );
        event = outboxRepository.saveAndFlush(event);
        deadLetterHandoffRepository.saveAndFlush(
                new PaymentOutboxDeadLetterHandoff(event)
        );
        event.markPublished(publishedAt.truncatedTo(ChronoUnit.MICROS));
        Payment payment = paymentRepository.findById(event.getPayment().getId())
                .orElseThrow();
        payment.setStatus("PUBLISHED");
        paymentRepository.saveAndFlush(payment);
        return outboxRepository.saveAndFlush(event);
    }

    private PaymentOutboxEvent persistPending() {
        return outboxRepository.saveAndFlush(newEvent("CREATED"));
    }

    private PaymentOutboxEvent persistRetrying() {
        PaymentOutboxEvent event = newEvent("PENDING_RETRY");
        event.scheduleRetry(CUTOFF.minusSeconds(10), "TimeoutException");
        return outboxRepository.saveAndFlush(event);
    }

    private PaymentOutboxEvent persistExhausted() {
        PaymentOutboxEvent event = newEvent("PUBLISH_EXHAUSTED");
        event.markExhausted(CUTOFF.minusSeconds(10), "TimeoutException");
        return outboxRepository.saveAndFlush(event);
    }

    private PaymentOutboxEvent newEvent(String paymentStatus) {
        fixtureSequence++;
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(String.format("%064d", fixtureSequence));
        payment.setFromAccount(100L + fixtureSequence);
        payment.setToAccount(200L + fixtureSequence);
        payment.setAmount(new BigDecimal("125.00"));
        payment.setStatus(paymentStatus);
        Payment savedPayment = paymentRepository.saveAndFlush(payment);
        Instant createdAt = CUTOFF.minus(100, ChronoUnit.DAYS)
                .plusSeconds(fixtureSequence)
                .truncatedTo(ChronoUnit.MICROS);
        return new PaymentOutboxEvent(
                savedPayment,
                "payments",
                savedPayment.getId().toString(),
                savedPayment.getId() + "|101|201|125.00",
                createdAt
        );
    }

    private PaymentOutboxRecoveryAudit persistRecoveryAudit(PaymentOutboxEvent event) {
        fixtureSequence++;
        Instant requeuedAt = CUTOFF.minus(50, ChronoUnit.DAYS)
                .plusSeconds(fixtureSequence)
                .truncatedTo(ChronoUnit.MICROS);
        return recoveryAuditRepository.saveAndFlush(new PaymentOutboxRecoveryAudit(
                event,
                String.format("%064x", fixtureSequence),
                "retention-test-operator",
                "Recovered after the broker was restored",
                requeuedAt,
                requeuedAt.minusSeconds(30),
                5,
                "TimeoutException"
        ));
    }

    private PaymentOutboxRecoveryRejectionAudit persistRejection(Instant rejectedAt) {
        fixtureSequence++;
        return rejectionAuditRepository.saveAndFlush(
                new PaymentOutboxRecoveryRejectionAudit(
                        10_000L + fixtureSequence,
                        PaymentOutboxRecoveryRejectionCode.EVENT_NOT_FOUND,
                        rejectedAt.truncatedTo(ChronoUnit.MICROS)
                )
        );
    }

    private PaymentOutboxDeadLetterHandoff onlyHandoff(PaymentOutboxEvent event) {
        return deadLetterHandoffRepository.findByOutboxEventId(event.getId())
                .get(0);
    }
}
