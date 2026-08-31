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
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"))
        );

        assertThat(created.getId()).isNotNull();
        entityManager.flush();
        entityManager.clear();

        List<Payment> payments = paymentService.findAll();

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(payments).singleElement().satisfies(payment -> {
            assertThat(payment.getId()).isEqualTo(created.getId());
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
}
