package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(PaymentCreationTransaction.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentCreationRollbackPersistenceTest {

    @Autowired
    private PaymentCreationTransaction paymentCreationTransaction;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockBean
    private PaymentOutboxRepository paymentOutboxRepository;

    @Test
    void create_outboxFailureRollsBackRealPaymentInsert() {
        when(paymentOutboxRepository.saveAndFlush(any(PaymentOutboxEvent.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> paymentCreationTransaction.create(
                "a".repeat(64),
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(paymentRepository.count()).isZero();
    }
}
