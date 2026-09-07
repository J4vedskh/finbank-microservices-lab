package com.banking.payment.messaging;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(PaymentOutboxPublisher.class)
@TestPropertySource(properties = {
        "payment.outbox.send-timeout-ms=1000",
        "payment.outbox.retry-delay-ms=5000"
})
class PaymentOutboxPublisherPersistenceTest {

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
    void publishNext_acknowledgementPersistsPublishedState() {
        PaymentOutboxEvent event = persistPendingEvent();
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(publisher.publishNext()).isTrue();
        entityManager.flush();
        entityManager.clear();

        assertThat(paymentOutboxRepository.findById(event.getId()))
                .hasValueSatisfying(persisted -> {
                    assertThat(persisted.getStatus()).isEqualTo(PaymentOutboxStatus.PUBLISHED);
                    assertThat(persisted.getAttemptCount()).isEqualTo(1);
                    assertThat(persisted.getPublishedAt()).isNotNull();
                    assertThat(persisted.getLastError()).isNull();
                    assertThat(persisted.getPayment().getStatus()).isEqualTo("PUBLISHED");
                });
    }

    @Test
    void publishNext_failurePersistsRetryState() {
        PaymentOutboxEvent event = persistPendingEvent();
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
                .thenReturn(failed);

        assertThat(publisher.publishNext()).isTrue();
        entityManager.flush();
        entityManager.clear();

        assertThat(paymentOutboxRepository.findById(event.getId()))
                .hasValueSatisfying(persisted -> {
                    assertThat(persisted.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
                    assertThat(persisted.getAttemptCount()).isEqualTo(1);
                    assertThat(persisted.getPublishedAt()).isNull();
                    assertThat(persisted.getLastError()).isEqualTo("IllegalStateException");
                    assertThat(persisted.getPayment().getStatus()).isEqualTo("PENDING_RETRY");
                });
    }

    private PaymentOutboxEvent persistPendingEvent() {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash("a".repeat(64));
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        Instant createdAt = Instant.now().minusSeconds(1);
        return paymentOutboxRepository.saveAndFlush(new PaymentOutboxEvent(
                savedPayment,
                "payments",
                savedPayment.getId().toString(),
                savedPayment.getId() + "|1|2|750.00",
                createdAt
        ));
    }
}
