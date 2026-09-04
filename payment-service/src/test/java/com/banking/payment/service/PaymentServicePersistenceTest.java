package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import(PaymentService.class)
class PaymentServicePersistenceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TestEntityManager entityManager;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void create_persistsGeneratedIdentityAndUsesItInTheEventRequest() {
        Payment created = paymentService.create(
                "pay-key-42",
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"))
        );

        assertThat(created.getId()).isNotNull();
        assertThat(created.getIdempotencyKeyHash()).matches("[0-9a-f]{64}");
        entityManager.flush();
        entityManager.clear();

        List<Payment> payments = paymentService.findAll();

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(payments).singleElement().satisfies(payment -> {
            assertThat(payment.getId()).isEqualTo(created.getId());
            assertThat(payment.getIdempotencyKeyHash())
                    .isEqualTo(created.getIdempotencyKeyHash());
            assertThat(payment.getFromAccount()).isEqualTo(1L);
            assertThat(payment.getToAccount()).isEqualTo(2L);
            assertThat(payment.getAmount()).isEqualByComparingTo("750.00");
            assertThat(payment.getStatus()).isEqualTo("CREATED");
        });
        verify(kafkaTemplate).send(
                "payments",
                created.getId() + "|1|2|750.00"
        );
    }

    @Test
    void create_exactReplayKeepsOnePaymentAndOneEventRequest() {
        CreatePaymentRequest request =
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"));

        Payment first = paymentService.create("pay-key-42", request);
        entityManager.flush();
        entityManager.clear();

        Payment replay = paymentService.create("pay-key-42", request);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(paymentRepository.count()).isEqualTo(1);
        verify(kafkaTemplate, times(1)).send(
                "payments",
                first.getId() + "|1|2|750.00"
        );
    }

    @Test
    void save_duplicateIdempotencyKeyHash_isRejectedByDatabase() {
        paymentRepository.saveAndFlush(payment("a".repeat(64), 1L, 2L));

        assertThatThrownBy(() ->
                paymentRepository.saveAndFlush(payment("a".repeat(64), 3L, 4L))
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

    private Payment payment(String idempotencyKeyHash, Long fromAccount, Long toAccount) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(idempotencyKeyHash);
        payment.setFromAccount(fromAccount);
        payment.setToAccount(toAccount);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        return payment;
    }
}
