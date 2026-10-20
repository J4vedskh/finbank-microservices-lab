package com.banking.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
class PaymentOutboxRepositoryPersistenceTest {

    private static final Instant CUTOFF = Instant.parse("2026-10-20T05:30:00Z");

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void stuckCountIncludesOnlyOldPendingNonExhaustedEvents() {
        PaymentOutboxEvent oldPendingRetry = event("a", CUTOFF.minusSeconds(600));
        oldPendingRetry.scheduleRetry(CUTOFF.plusSeconds(30), "TimeoutException");

        PaymentOutboxEvent freshPending = event("b", CUTOFF.plusSeconds(1));

        PaymentOutboxEvent exhaustedPending = event("c", CUTOFF.minusSeconds(600));
        exhaustedPending.markExhausted(CUTOFF.minusSeconds(1), "TimeoutException");

        PaymentOutboxEvent published = event("d", CUTOFF.minusSeconds(600));
        published.markPublished(CUTOFF.minusSeconds(1));

        paymentOutboxRepository.saveAllAndFlush(List.of(
                oldPendingRetry,
                freshPending,
                exhaustedPending,
                published
        ));
        entityManager.clear();

        assertThat(paymentOutboxRepository
                .countByStatusAndExhaustedAtIsNullAndCreatedAtBefore(
                        PaymentOutboxStatus.PENDING,
                        CUTOFF
                )).isEqualTo(1L);
    }

    private PaymentOutboxEvent event(String hashCharacter, Instant createdAt) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(hashCharacter.repeat(64));
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        Payment savedPayment = paymentRepository.saveAndFlush(payment);
        return new PaymentOutboxEvent(
                savedPayment,
                "payments",
                savedPayment.getId().toString(),
                savedPayment.getId() + "|1|2|750.00",
                createdAt
        );
    }
}
