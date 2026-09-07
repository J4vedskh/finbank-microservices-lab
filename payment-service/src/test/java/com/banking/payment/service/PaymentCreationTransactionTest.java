package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCreationTransactionTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentOutboxRepository paymentOutboxRepository;

    @InjectMocks
    private PaymentCreationTransaction paymentCreationTransaction;

    @Test
    void create_persistsPaymentBeforeItsStableOutboxEvent() {
        CreatePaymentRequest request =
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(42L);
            return payment;
        });

        Payment result = paymentCreationTransaction.create("a".repeat(64), request);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        ArgumentCaptor<PaymentOutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(PaymentOutboxEvent.class);
        InOrder paymentThenOutbox = inOrder(paymentRepository, paymentOutboxRepository);
        paymentThenOutbox.verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        paymentThenOutbox.verify(paymentOutboxRepository).saveAndFlush(eventCaptor.capture());

        Payment payment = paymentCaptor.getValue();
        PaymentOutboxEvent event = eventCaptor.getValue();
        assertThat(payment.getIdempotencyKeyHash()).isEqualTo("a".repeat(64));
        assertThat(payment.getStatus()).isEqualTo("CREATED");
        assertThat(event.getPayment()).isSameAs(payment);
        assertThat(event.getTopic()).isEqualTo("payments");
        assertThat(event.getEventKey()).isEqualTo("42");
        assertThat(event.getPayload()).isEqualTo("42|1|2|750.00");
        assertThat(event.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getCreatedAt()).isEqualTo(event.getNextAttemptAt());
        assertThat(result).isSameAs(payment);
    }

    @Test
    void create_outboxFailure_propagatesSoTheTransactionCanRollBack() {
        CreatePaymentRequest request =
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(42L);
            return payment;
        });
        when(paymentOutboxRepository.saveAndFlush(any(PaymentOutboxEvent.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() ->
                paymentCreationTransaction.create("a".repeat(64), request)
        ).isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
    }
}
