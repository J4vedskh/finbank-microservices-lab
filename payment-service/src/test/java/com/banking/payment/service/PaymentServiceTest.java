package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(paymentRepository.findByIdempotencyKeyHash(anyString())).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(42L);
            return payment;
        });

        Payment result = paymentService.create("pay-key-42", request);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        InOrder persistenceThenEnqueue = inOrder(paymentRepository, kafkaTemplate);
        persistenceThenEnqueue.verify(paymentRepository)
                .findByIdempotencyKeyHash(hashCaptor.capture());
        persistenceThenEnqueue.verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        persistenceThenEnqueue.verify(kafkaTemplate).send("payments", "42|1|2|750.00");

        assertThat(hashCaptor.getValue()).matches("[0-9a-f]{64}");
        assertThat(hashCaptor.getValue()).isNotEqualTo("pay-key-42");
        Payment persisted = paymentCaptor.getValue();
        assertThat(persisted.getId()).isEqualTo(42L);
        assertThat(persisted.getIdempotencyKeyHash()).isEqualTo(hashCaptor.getValue());
        assertThat(persisted.getFromAccount()).isEqualTo(1L);
        assertThat(persisted.getToAccount()).isEqualTo(2L);
        assertThat(persisted.getAmount()).isEqualByComparingTo("750.00");
        assertThat(persisted.getStatus()).isEqualTo("CREATED");
        assertThat(result).isSameAs(persisted);
    }

    @Test
    void create_exactReplay_returnsExistingPaymentWithoutPublishingAgain() {
        Payment existing = payment(42L, 1L, 2L, "750.0");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.of(existing));

        Payment result = paymentService.create(
                "pay-key-42",
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"))
        );

        assertThat(result).isSameAs(existing);
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verifyNoInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @MethodSource("conflictingRequests")
    void create_keyReusedForDifferentRequest_throwsConflict(CreatePaymentRequest request) {
        Payment existing = payment(42L, 1L, 2L, "750.00");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> paymentService.create("pay-key-42", request))
                .isInstanceOf(PaymentIdempotencyConflictException.class)
                .hasMessage("Idempotency key is already associated with a different payment");

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void create_identicalConcurrentInsert_returnsWinningPaymentWithoutPublishing() {
        Payment existing = payment(42L, 1L, 2L, "750.0");
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("duplicate idempotency key hash");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(race);

        Payment result = paymentService.create(
                "pay-key-42",
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"))
        );

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void create_conflictingConcurrentInsert_throwsConflictWithoutPublishing() {
        Payment existing = payment(42L, 1L, 3L, "750.00");
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("duplicate idempotency key hash");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(race);

        assertThatThrownBy(() -> paymentService.create(
                "pay-key-42",
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"))
        )).isInstanceOf(PaymentIdempotencyConflictException.class);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void create_constraintFailureWithoutMatchingKey_propagatesOriginalFailure() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated constraint failure");
        when(paymentRepository.findByIdempotencyKeyHash(anyString())).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(failure);

        assertThatThrownBy(() -> paymentService.create(
                "pay-key-42",
                new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"))
        )).isSameAs(failure);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void create_doesNotPublishWhenPersistenceFails() {
        CreatePaymentRequest request = new CreatePaymentRequest(1L, 2L, new BigDecimal("750.00"));
        when(paymentRepository.findByIdempotencyKeyHash(anyString())).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> paymentService.create("pay-key-42", request))
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

    private static Stream<Arguments> conflictingRequests() {
        return Stream.of(
                Arguments.of(new CreatePaymentRequest(3L, 2L, new BigDecimal("750.00"))),
                Arguments.of(new CreatePaymentRequest(1L, 3L, new BigDecimal("750.00"))),
                Arguments.of(new CreatePaymentRequest(1L, 2L, new BigDecimal("751.00")))
        );
    }

    private Payment payment(Long id, Long fromAccount, Long toAccount, String amount) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setIdempotencyKeyHash("a".repeat(64));
        payment.setFromAccount(fromAccount);
        payment.setToAccount(toAccount);
        payment.setAmount(new BigDecimal(amount));
        payment.setStatus("CREATED");
        return payment;
    }
}
