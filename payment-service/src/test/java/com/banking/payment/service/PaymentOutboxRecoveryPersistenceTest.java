package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxDeadLetterHandoff;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.messaging.PaymentOutboxPublisher;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({
        PaymentOutboxRecoveryService.class,
        PaymentOutboxRecoveryTransaction.class,
        PaymentOutboxRecoveryRejectionAuditTransaction.class,
        PaymentOutboxPublisher.class
})
@TestPropertySource(properties = {
        "payment.outbox.send-timeout-ms=1000",
        "payment.outbox.retry-delay-ms=5000",
        "payment.outbox.max-retry-delay-ms=60000",
        "payment.outbox.max-attempts=5"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxRecoveryPersistenceTest {
    private static final String COMMAND_KEY = "recovery-command-0001";
    private static final String ACTOR = "portfolio-operator";
    private static final String REASON = "Re-arm after broker connectivity was restored";

    @Autowired
    private PaymentOutboxRecoveryService recoveryService;

    @Autowired
    private PaymentOutboxPublisher publisher;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private PaymentOutboxRecoveryAuditRepository auditRepository;

    @Autowired
    private PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository;

    @Autowired
    private PaymentOutboxDeadLetterHandoffRepository deadLetterHandoffRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @AfterEach
    void clearCommittedFixtures() {
        deadLetterHandoffRepository.deleteAllInBatch();
        rejectionAuditRepository.deleteAllInBatch();
        auditRepository.deleteAllInBatch();
        paymentOutboxRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
    }

    @Test
    void requeueExhausted_persistsAuditAndFreshCycleAtomicallyWithoutPublishing() {
        PaymentOutboxEvent event = persistExhaustedEvent("a".repeat(64));
        Long paymentId = event.getPayment().getId();
        Instant exhaustedAt = event.getExhaustedAt();
        int attemptCount = event.getAttemptCount();
        Instant beforeRecovery = Instant.now();

        PaymentOutboxRecoveryAudit audit = recoveryService
                .requeueExhausted(event.getId(), COMMAND_KEY, ACTOR, REASON);
        PaymentOutboxEvent recovered = paymentOutboxRepository.findById(event.getId())
                .orElseThrow();
        PaymentOutboxRecoveryAudit storedAudit = auditRepository.findById(audit.getId())
                .orElseThrow();
        assertThat(recovered.getAttemptCount()).isZero();
        assertThat(recovered.getExhaustedAt()).isNull();
        assertThat(recovered.getNextAttemptAt()).isAfterOrEqualTo(beforeRecovery);
        assertThat(recovered.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(recovered.getLastError()).isEqualTo("TimeoutException");
        assertThat(paymentRepository.findById(paymentId))
                .hasValueSatisfying(payment ->
                        assertThat(payment.getStatus()).isEqualTo("PENDING_RETRY"));
        assertThat(storedAudit.getOutboxEvent().getId()).isEqualTo(event.getId());
        assertThat(storedAudit.getRecoveryKeyHash()).hasSize(64).doesNotContain(COMMAND_KEY);
        assertThat(storedAudit.getActor()).isEqualTo(ACTOR);
        assertThat(storedAudit.getReason()).isEqualTo(REASON);
        assertThat(storedAudit.getRequeuedAt()).isAfterOrEqualTo(beforeRecovery);
        assertThat(storedAudit.getPreviousExhaustedAt()).isEqualTo(exhaustedAt);
        assertThat(storedAudit.getPreviousAttemptCount()).isEqualTo(attemptCount);
        assertThat(storedAudit.getPreviousLastError()).isEqualTo("TimeoutException");
        assertThat(rejectionAuditRepository.count()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void requeueExhausted_exactReplayReturnsSameAuditWithoutStartingAnotherCycle() {
        PaymentOutboxEvent event = persistExhaustedEvent("b".repeat(64));

        PaymentOutboxRecoveryAudit first = recoveryService
                .requeueExhausted(event.getId(), COMMAND_KEY, ACTOR, REASON);
        PaymentOutboxEvent afterFirst = paymentOutboxRepository.findById(event.getId())
                .orElseThrow();
        Instant retryAt = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        afterFirst.scheduleRetry(retryAt, "RetryFailure");
        paymentOutboxRepository.saveAndFlush(afterFirst);

        PaymentOutboxRecoveryAudit replay = recoveryService
                .requeueExhausted(event.getId(), COMMAND_KEY, ACTOR, REASON);

        PaymentOutboxEvent unchanged = paymentOutboxRepository.findById(event.getId())
                .orElseThrow();
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(auditRepository.count()).isEqualTo(1);
        assertThat(unchanged.getAttemptCount()).isEqualTo(1);
        assertThat(unchanged.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(unchanged.getLastError()).isEqualTo("RetryFailure");
        assertThat(rejectionAuditRepository.count()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void requeueExhausted_normalPublisherCanAcknowledgeRecoveredEvent() {
        PaymentOutboxEvent event = persistExhaustedEvent("c".repeat(64));
        Long paymentId = event.getPayment().getId();
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        recoveryService.requeueExhausted(event.getId(), COMMAND_KEY, ACTOR, REASON);
        assertThat(publisher.publishNext()).isTrue();

        assertThat(paymentOutboxRepository.findById(event.getId()))
                .hasValueSatisfying(published -> {
                    assertThat(published.getStatus()).isEqualTo(PaymentOutboxStatus.PUBLISHED);
                    assertThat(published.getAttemptCount()).isEqualTo(1);
                    assertThat(published.getPublishedAt()).isNotNull();
                    assertThat(published.getExhaustedAt()).isNull();
                    assertThat(published.getLastError()).isNull();
                });
        assertThat(paymentRepository.findById(paymentId))
                .hasValueSatisfying(payment ->
                        assertThat(payment.getStatus()).isEqualTo("PUBLISHED"));
        assertThat(auditRepository.count()).isEqualTo(1);
        assertThat(rejectionAuditRepository.count()).isZero();
        verify(kafkaTemplate).send(event.getTopic(), event.getEventKey(), event.getPayload());
    }

    @Test
    void requeueThenLaterExhaustion_preservesBothDeadLetterHandoffCycles() {
        PaymentOutboxEvent event = persistExhaustedEvent("d".repeat(64));
        Long paymentId = event.getPayment().getId();
        PaymentOutboxDeadLetterHandoff firstHandoff = deadLetterHandoffRepository
                .saveAndFlush(new PaymentOutboxDeadLetterHandoff(event));

        recoveryService.requeueExhausted(event.getId(), COMMAND_KEY, ACTOR, REASON);
        PaymentOutboxEvent recovered = paymentOutboxRepository.findById(event.getId())
                .orElseThrow();
        Instant dueAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        recovered.scheduleRetry(dueAt.plusSeconds(1), "Failure1");
        recovered.scheduleRetry(dueAt.plusSeconds(2), "Failure2");
        recovered.scheduleRetry(dueAt.plusSeconds(3), "Failure3");
        recovered.scheduleRetry(dueAt.plusSeconds(4), "Failure4");
        paymentOutboxRepository.saveAndFlush(recovered);
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        payment.setStatus("PENDING_RETRY");
        paymentRepository.saveAndFlush(payment);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(
                new IllegalStateException("secret broker failure detail")
        );
        when(kafkaTemplate.send(
                recovered.getTopic(),
                recovered.getEventKey(),
                recovered.getPayload()
        )).thenReturn(failed);

        assertThat(publisher.publishNext()).isTrue();

        assertThat(deadLetterHandoffRepository.findByOutboxEventId(event.getId()))
                .satisfiesExactly(
                        first -> {
                            assertThat(first.getId()).isEqualTo(firstHandoff.getId());
                            assertThat(first.getExhaustionSequence()).isEqualTo(1);
                            assertThat(first.getAttemptCount()).isEqualTo(5);
                            assertThat(first.getFailureType()).isEqualTo("TimeoutException");
                        },
                        second -> {
                            assertThat(second.getExhaustionSequence()).isEqualTo(2);
                            assertThat(second.getAttemptCount()).isEqualTo(5);
                            assertThat(second.getFailureType())
                                    .isEqualTo("IllegalStateException")
                                    .doesNotContain("secret broker failure detail");
                            assertThat(second.getExhaustedAt())
                                    .isAfter(firstHandoff.getExhaustedAt());
                        }
                );
        assertThat(auditRepository.count()).isEqualTo(1);
        assertThat(paymentOutboxRepository.findById(event.getId()))
                .hasValueSatisfying(exhausted -> {
                    assertThat(exhausted.getExhaustionSequence()).isEqualTo(2);
                    assertThat(exhausted.getExhaustedAt()).isNotNull();
                });
        assertThat(paymentRepository.findById(paymentId))
                .hasValueSatisfying(exhaustedPayment ->
                        assertThat(exhaustedPayment.getStatus())
                                .isEqualTo("PUBLISH_EXHAUSTED"));
        verify(kafkaTemplate).send(
                recovered.getTopic(),
                recovered.getEventKey(),
                recovered.getPayload()
        );
    }

    private PaymentOutboxEvent persistExhaustedEvent(String paymentKeyHash) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(paymentKeyHash);
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
