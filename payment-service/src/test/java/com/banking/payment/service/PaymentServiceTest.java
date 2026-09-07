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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCreationTransaction paymentCreationTransaction;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void create_newRequest_delegatesHashedKeyToAtomicCreation() {
        CreatePaymentRequest request = request(1L, 2L, "750.00");
        Payment created = payment(42L, 1L, 2L, "750.00");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.empty());
        when(paymentCreationTransaction.create(anyString(), eq(request)))
                .thenReturn(created);

        Payment result = paymentService.create("pay-key-42", request);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        InOrder lookupThenCreate = inOrder(paymentRepository, paymentCreationTransaction);
        lookupThenCreate.verify(paymentRepository)
                .findByIdempotencyKeyHash(hashCaptor.capture());
        lookupThenCreate.verify(paymentCreationTransaction)
                .create(hashCaptor.getValue(), request);
        assertThat(hashCaptor.getValue()).matches("[0-9a-f]{64}");
        assertThat(hashCaptor.getValue()).isNotEqualTo("pay-key-42");
        assertThat(result).isSameAs(created);
    }

    @Test
    void create_exactReplay_returnsExistingPaymentWithoutAnotherOutboxWrite() {
        Payment existing = payment(42L, 1L, 2L, "750.0");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.of(existing));

        Payment result = paymentService.create(
                "pay-key-42",
                request(1L, 2L, "750.00")
        );

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(paymentCreationTransaction);
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

        verifyNoInteractions(paymentCreationTransaction);
    }

    @Test
    void create_identicalConcurrentInsert_returnsWinningPayment() {
        CreatePaymentRequest request = request(1L, 2L, "750.00");
        Payment existing = payment(42L, 1L, 2L, "750.0");
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("duplicate idempotency key hash");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(paymentCreationTransaction.create(anyString(), eq(request))).thenThrow(race);

        Payment result = paymentService.create("pay-key-42", request);

        assertThat(result).isSameAs(existing);
    }

    @Test
    void create_conflictingConcurrentInsert_throwsConflict() {
        CreatePaymentRequest request = request(1L, 2L, "750.00");
        Payment existing = payment(42L, 1L, 3L, "750.00");
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("duplicate idempotency key hash");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(paymentCreationTransaction.create(anyString(), eq(request))).thenThrow(race);

        assertThatThrownBy(() -> paymentService.create("pay-key-42", request))
                .isInstanceOf(PaymentIdempotencyConflictException.class);
    }

    @Test
    void create_constraintFailureWithoutMatchingKey_propagatesOriginalFailure() {
        CreatePaymentRequest request = request(1L, 2L, "750.00");
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated constraint failure");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.empty());
        when(paymentCreationTransaction.create(anyString(), eq(request))).thenThrow(failure);

        assertThatThrownBy(() -> paymentService.create("pay-key-42", request))
                .isSameAs(failure);
    }

    @Test
    void create_creationFailure_propagatesUnchanged() {
        CreatePaymentRequest request = request(1L, 2L, "750.00");
        when(paymentRepository.findByIdempotencyKeyHash(anyString()))
                .thenReturn(Optional.empty());
        when(paymentCreationTransaction.create(anyString(), eq(request)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> paymentService.create("pay-key-42", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
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
                Arguments.of(request(3L, 2L, "750.00")),
                Arguments.of(request(1L, 3L, "750.00")),
                Arguments.of(request(1L, 2L, "751.00"))
        );
    }

    private static CreatePaymentRequest request(Long from, Long to, String amount) {
        return new CreatePaymentRequest(from, to, new BigDecimal(amount));
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
