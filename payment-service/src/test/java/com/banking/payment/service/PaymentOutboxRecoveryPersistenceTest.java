package com.banking.payment.service;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.messaging.PaymentOutboxPublisher;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({PaymentOutboxRecoveryService.class, PaymentOutboxPublisher.class})
@TestPropertySource(properties = {
        "payment.outbox.send-timeout-ms=1000",
        "payment.outbox.retry-delay-ms=5000",
        "payment.outbox.max-retry-delay-ms=60000",
        "payment.outbox.max-attempts=5"
})
class PaymentOutboxRecoveryPersistenceTest {

    @Autowired
    private PaymentOutboxRecoveryService recoveryService;

    @Autowired
    private PaymentOutboxPublisher publisher;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private TestEntityManager entityManager;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void requeueExhausted_persistsFreshCycleAndMakesEventDue() {
        PaymentOutboxEvent event = persistExhaustedEvent();
        entityManager.clear();
        Instant beforeRecovery = Instant.now();

        recoveryService.requeueExhausted(event.getId());
        entityManager.flush();
        entityManager.clear();

        PaymentOutboxEvent recovered = paymentOutboxRepository.findById(event.getId())
                .orElseThrow();
        assertThat(recovered.getAttemptCount()).isZero();
        assertThat(recovered.getExhaustedAt()).isNull();
        assertThat(recovered.getNextAttemptAt()).isAfterOrEqualTo(beforeRecovery);
        assertThat(recovered.getLastError()).isEqualTo("TimeoutException");
        assertThat(recovered.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
        assertThat(paymentOutboxRepository
                .findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        PaymentOutboxStatus.PENDING,
                        Instant.now()
                )).hasValueSatisfying(due -> assertThat(due.getId()).isEqualTo(event.getId()));
    }

    @Test
    void requeueExhausted_normalPublisherCanAcknowledgeRecoveredEvent() {
        PaymentOutboxEvent event = persistExhaustedEvent();
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        recoveryService.requeueExhausted(event.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(publisher.publishNext()).isTrue();
        entityManager.flush();
        entityManager.clear();

        assertThat(paymentOutboxRepository.findById(event.getId()))
                .hasValueSatisfying(published -> {
                    assertThat(published.getStatus()).isEqualTo(PaymentOutboxStatus.PUBLISHED);
                    assertThat(published.getAttemptCount()).isEqualTo(1);
                    assertThat(published.getPublishedAt()).isNotNull();
                    assertThat(published.getExhaustedAt()).isNull();
                    assertThat(published.getLastError()).isNull();
                    assertThat(published.getPayment().getStatus()).isEqualTo("PUBLISHED");
                });
        verify(kafkaTemplate).send(event.getTopic(), event.getEventKey(), event.getPayload());
    }

    private PaymentOutboxEvent persistExhaustedEvent() {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash("a".repeat(64));
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("PUBLISH_EXHAUSTED");
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        Instant createdAt = Instant.now().minusSeconds(30);
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
