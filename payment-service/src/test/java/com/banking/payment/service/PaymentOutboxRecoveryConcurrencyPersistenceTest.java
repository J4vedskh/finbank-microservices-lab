package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import({PaymentOutboxRecoveryService.class, PaymentOutboxRecoveryTransaction.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxRecoveryConcurrencyPersistenceTest {
    private static final String COMMAND_KEY = "shared-recovery-command-0001";
    private static final String ACTOR = "portfolio-operator";
    private static final String REASON = "Re-arm after broker connectivity was restored";

    @Autowired
    private PaymentOutboxRecoveryService recoveryService;

    @SpyBean
    private PaymentOutboxRecoveryTransaction recoveryTransaction;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private PaymentOutboxRecoveryAuditRepository auditRepository;

    @Test
    void requeueExhausted_concurrentKeyReuseChoosesOneCommandAndRollsBackTheOther() throws Exception {
        PaymentOutboxEvent first = persistExhaustedEvent("1".repeat(64));
        PaymentOutboxEvent second = persistExhaustedEvent("2".repeat(64));
        Map<Long, Long> paymentIds = Map.of(
                first.getId(), first.getPayment().getId(),
                second.getId(), second.getPayment().getId()
        );
        CyclicBarrier bothTransactionsEntered = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothTransactionsEntered.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(recoveryTransaction).requeueExhausted(
                anyLong(),
                anyString(),
                anyString(),
                anyString()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RecoveryAttempt> firstAttempt = executor.submit(() -> recover(first.getId()));
            Future<RecoveryAttempt> secondAttempt = executor.submit(() -> recover(second.getId()));
            List<RecoveryAttempt> attempts = List.of(
                    firstAttempt.get(15, TimeUnit.SECONDS),
                    secondAttempt.get(15, TimeUnit.SECONDS)
            );

            assertThat(attempts).filteredOn(RecoveryAttempt::succeeded).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.succeeded()).singleElement()
                    .satisfies(attempt -> {
                        assertThat(attempt.failure())
                                .isInstanceOf(PaymentOutboxRecoveryCommandConflictException.class)
                                .hasMessageNotContaining(COMMAND_KEY);
                    });
            verify(recoveryTransaction, times(2)).requeueExhausted(
                    anyLong(),
                    anyString(),
                    anyString(),
                    anyString()
            );

            assertThat(auditRepository.count()).isEqualTo(1);
            PaymentOutboxRecoveryAudit storedAudit = auditRepository.findAll().get(0);
            Long winningEventId = storedAudit.getOutboxEvent().getId();
            Long losingEventId = attempts.stream()
                    .filter(attempt -> !attempt.succeeded())
                    .map(RecoveryAttempt::eventId)
                    .findFirst()
                    .orElseThrow();
            assertThat(winningEventId).isNotEqualTo(losingEventId);

            assertThat(paymentOutboxRepository.findById(winningEventId)).hasValueSatisfying(event -> {
                assertThat(event.getAttemptCount()).isZero();
                assertThat(event.getExhaustedAt()).isNull();
            });
            assertThat(paymentRepository.findById(paymentIds.get(winningEventId)))
                    .hasValueSatisfying(payment ->
                            assertThat(payment.getStatus()).isEqualTo("PENDING_RETRY"));
            assertThat(paymentOutboxRepository.findById(losingEventId)).hasValueSatisfying(event -> {
                assertThat(event.getAttemptCount()).isEqualTo(5);
                assertThat(event.getExhaustedAt()).isNotNull();
                assertThat(event.getLastError()).isEqualTo("TimeoutException");
            });
            assertThat(paymentRepository.findById(paymentIds.get(losingEventId)))
                    .hasValueSatisfying(payment ->
                            assertThat(payment.getStatus()).isEqualTo("PUBLISH_EXHAUSTED"));
        } finally {
            executor.shutdownNow();
        }
    }

    private RecoveryAttempt recover(Long eventId) {
        try {
            PaymentOutboxRecoveryAudit audit = recoveryService
                    .requeueExhausted(eventId, COMMAND_KEY, ACTOR, REASON);
            return new RecoveryAttempt(eventId, audit.getId(), null);
        } catch (RuntimeException failure) {
            return new RecoveryAttempt(eventId, null, failure);
        }
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

    private record RecoveryAttempt(Long eventId, Long auditId, RuntimeException failure) {
        private boolean succeeded() {
            return failure == null && auditId != null;
        }
    }
}
