package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({PaymentService.class, PaymentCreationTransaction.class})
class PaymentServicePersistenceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void create_commitsPaymentAndPendingOutboxEventTogether() {
        Payment created = paymentService.create(
                "pay-key-42",
                request()
        );

        assertThat(created.getId()).isNotNull();
        assertThat(created.getIdempotencyKeyHash()).matches("[0-9a-f]{64}");
        entityManager.flush();
        entityManager.clear();

        List<PaymentOutboxEvent> outboxEvents = paymentOutboxRepository.findAll();

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxEvents).singleElement().satisfies(event -> {
            assertThat(event.getPayment().getId()).isEqualTo(created.getId());
            assertThat(event.getTopic()).isEqualTo("payments");
            assertThat(event.getEventKey()).isEqualTo(created.getId().toString());
            assertThat(event.getPayload())
                    .isEqualTo(created.getId() + "|1|2|750.00");
            assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
            assertThat(event.getAttemptCount()).isZero();
            assertThat(event.getCreatedAt()).isNotNull();
            assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
            assertThat(event.getPublishedAt()).isNull();
            assertThat(event.getLastError()).isNull();
        });
    }

    @Test
    void create_exactReplayKeepsOnePaymentAndOneOutboxEvent() {
        Payment first = paymentService.create("pay-key-42", request());
        entityManager.flush();
        entityManager.clear();

        Payment replay = paymentService.create("pay-key-42", request());

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(paymentOutboxRepository.count()).isEqualTo(1);
    }

    @Test
    void save_duplicateIdempotencyKeyHash_isRejectedByDatabase() {
        paymentRepository.saveAndFlush(payment("a".repeat(64), 1L, 2L));

        assertThatThrownBy(() ->
                paymentRepository.saveAndFlush(payment("a".repeat(64), 3L, 4L))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_secondOutboxEventForPayment_isRejectedByDatabase() {
        Payment payment = paymentRepository.saveAndFlush(
                payment("a".repeat(64), 1L, 2L)
        );
        Instant createdAt = Instant.now();
        paymentOutboxRepository.saveAndFlush(outboxEvent(payment, createdAt));

        assertThatThrownBy(() ->
                paymentOutboxRepository.saveAndFlush(outboxEvent(payment, createdAt.plusSeconds(1)))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_legacyPaymentWithoutIdempotencyKeyHash_remainsReadable() {
        Payment saved = paymentRepository.saveAndFlush(payment(null, 1L, 2L));
        entityManager.clear();

        assertThat(paymentRepository.findById(saved.getId()))
                .hasValueSatisfying(payment ->
                        assertThat(payment.getIdempotencyKeyHash()).isNull()
                );
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"));
    }

    private Payment payment(String idempotencyKeyHash, Long fromAccount, Long toAccount) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(idempotencyKeyHash);
        payment.setFromAccount(fromAccount);
        payment.setToAccount(toAccount);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        return payment;
    }

    private PaymentOutboxEvent outboxEvent(Payment payment, Instant createdAt) {
        return new PaymentOutboxEvent(
                payment,
                "payments",
                payment.getId().toString(),
                payment.getId() + "|1|2|750.00",
                createdAt
        );
    }
}
