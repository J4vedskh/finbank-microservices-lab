package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void create_savesCreatedPaymentBeforeRequestingEventPublication() {
        CreatePaymentRequest request = new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(42L);
            return payment;
        });

        Payment result = paymentService.create(request);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        InOrder persistenceThenEnqueue = inOrder(paymentRepository, kafkaTemplate);
        persistenceThenEnqueue.verify(paymentRepository).save(paymentCaptor.capture());
        persistenceThenEnqueue.verify(kafkaTemplate).send("payments", "42|1|2|750.00");

        Payment persisted = paymentCaptor.getValue();
        assertThat(persisted.getId()).isEqualTo(42L);
        assertThat(persisted.getFromAccount()).isEqualTo(1L);
        assertThat(persisted.getToAccount()).isEqualTo(2L);
        assertThat(persisted.getAmount()).isEqualByComparingTo("750.00");
        assertThat(persisted.getStatus()).isEqualTo("CREATED");
        assertThat(result).isSameAs(persisted);
    }

    @Test
    void create_doesNotPublishWhenPersistenceFails() {
        CreatePaymentRequest request = new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> paymentService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void findAll_returnsRepositoryResults() {
        Payment payment = new Payment();
        payment.setId(42L);
        when(paymentRepository.findAll()).thenReturn(List.of(payment));

        List<Payment> result = paymentService.findAll();

        assertThat(result).containsExactly(payment);
    }
}
