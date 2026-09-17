package com.banking.payment.messaging;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxDeadLetterHandoff;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(PaymentOutboxPublisher.class)
@TestPropertySource(properties = {
        "payment.outbox.send-timeout-ms=1000",
        "payment.outbox.retry-delay-ms=5000",
        "payment.outbox.max-retry-delay-ms=60000",
        "payment.outbox.max-attempts=3"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentOutboxDeadLetterRollbackPersistenceTest {

    @Autowired
    private PaymentOutboxPublisher publisher;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @MockBean
    private PaymentOutboxDeadLetterHandoffRepository deadLetterHandoffRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @AfterEach
    void clearCommittedFixtures() {
        outboxRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
    }

    @Test
    void finalSendFailure_handoffWriteFailureRollsBackTerminalState() {
        PaymentOutboxEvent event = persistEventBeforeFinalAttempt();
        Long eventId = event.getId();
        Long paymentId = event.getPayment().getId();
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
                .thenReturn(failed);
        when(deadLetterHandoffRepository.saveAndFlush(
                any(PaymentOutboxDeadLetterHandoff.class)
        )).thenThrow(new DataIntegrityViolationException("handoff unavailable"));

        assertThatThrownBy(publisher::publishNext)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("handoff unavailable");

        assertThat(outboxRepository.findById(eventId))
                .hasValueSatisfying(persisted -> {
                    assertThat(persisted.getAttemptCount()).isEqualTo(2);
                    assertThat(persisted.getExhaustedAt()).isNull();
                    assertThat(persisted.getExhaustionSequence()).isZero();
                    assertThat(persisted.getLastError()).isEqualTo("Failure2");
                });
        assertThat(paymentRepository.findById(paymentId))
                .hasValueSatisfying(payment ->
                        assertThat(payment.getStatus()).isEqualTo("PENDING_RETRY"));
        verify(kafkaTemplate).send(
                event.getTopic(),
                event.getEventKey(),
                event.getPayload()
        );
    }

    private PaymentOutboxEvent persistEventBeforeFinalAttempt() {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash("d".repeat(64));
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("PENDING_RETRY");
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        Instant createdAt = Instant.now().minusSeconds(30);
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                savedPayment,
                "payments",
                savedPayment.getId().toString(),
                savedPayment.getId() + "|1|2|750.00",
                createdAt
        );
        event.scheduleRetry(Instant.now().minusSeconds(2), "Failure1");
        event.scheduleRetry(Instant.now().minusSeconds(1), "Failure2");
        return outboxRepository.saveAndFlush(event);
    }
}
