package com.banking.transaction.service;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void recordPaymentEvent_validPayload_persistsCompletedTransaction() {
        when(transactionRepository.findByPaymentId(42L)).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            transaction.setId(99L);
            return transaction;
        });

        Transaction result = transactionService.recordPaymentEvent("42|1|2|750.00");

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(transactionCaptor.capture());
        Transaction persisted = transactionCaptor.getValue();
        assertThat(persisted.getId()).isEqualTo(99L);
        assertThat(persisted.getPaymentId()).isEqualTo(42L);
        assertThat(persisted.getFromAccount()).isEqualTo(1L);
        assertThat(persisted.getToAccount()).isEqualTo(2L);
        assertThat(persisted.getAmount()).isEqualByComparingTo("750.00");
        assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(result).isSameAs(persisted);
    }

    @Test
    void recordPaymentEvent_identicalReplay_returnsExistingLedgerEntry() {
        Transaction existing = completedTransaction(42L, 1L, 2L, "750.00");
        when(transactionRepository.findByPaymentId(42L)).thenReturn(Optional.of(existing));

        Transaction result = transactionService.recordPaymentEvent("42|1|2|750.00");

        assertThat(result).isSameAs(existing);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordPaymentEvent_conflictingReplay_failsBeforePersistence() {
        Transaction existing = completedTransaction(42L, 1L, 2L, "750.00");
        when(transactionRepository.findByPaymentId(42L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transactionService.recordPaymentEvent("42|1|3|750.00"))
                .isInstanceOf(PaymentEventConflictException.class)
                .hasMessage("Payment event 42 conflicts with its existing ledger entry");

        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    void recordPaymentEvent_identicalConcurrentInsert_returnsWinningLedgerEntry() {
        Transaction existing = completedTransaction(42L, 1L, 2L, "750.0");
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("duplicate payment id");
        when(transactionRepository.findByPaymentId(42L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenThrow(race);

        Transaction result = transactionService.recordPaymentEvent("42|1|2|750.00");

        assertThat(result).isSameAs(existing);
    }

    @Test
    void recordPaymentEvent_conflictingConcurrentInsert_throwsTypedConflict() {
        Transaction existing = completedTransaction(42L, 1L, 3L, "750.00");
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("duplicate payment id");
        when(transactionRepository.findByPaymentId(42L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenThrow(race);

        assertThatThrownBy(() -> transactionService.recordPaymentEvent("42|1|2|750.00"))
                .isInstanceOf(PaymentEventConflictException.class)
                .hasMessage("Payment event 42 conflicts with its existing ledger entry");
    }

    @Test
    void recordPaymentEvent_constraintFailureWithoutMatchingRow_propagatesOriginalFailure() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated constraint failure");
        when(transactionRepository.findByPaymentId(42L)).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenThrow(failure);

        assertThatThrownBy(() -> transactionService.recordPaymentEvent("42|1|2|750.00"))
                .isSameAs(failure);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "42|1|2",
            "42|1|2|750.00|unexpected",
            "42|1|2|",
            "payment|1|2|750.00",
            "42|source|2|750.00",
            "42|1|destination|750.00",
            "42|1|2|amount",
            "0|1|2|750.00",
            "42|0|2|750.00",
            "42|1|-2|750.00",
            "42|1|1|750.00",
            "42|1|2|0",
            "42|1|2|-0.01",
            "42|1|2|750.001"
    })
    void recordPaymentEvent_malformedPayload_failsBeforePersistence(String payload) {
        assertThatThrownBy(() -> transactionService.recordPaymentEvent(payload))
                .isInstanceOf(MalformedPaymentEventException.class)
                .hasMessageStartingWith("Invalid payment event:");

        verifyNoInteractions(transactionRepository);
    }

    @Test
    void recordPaymentEvent_repositoryFailure_propagatesUnchanged() {
        when(transactionRepository.findByPaymentId(42L)).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> transactionService.recordPaymentEvent("42|1|2|750.00"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void findAll_withoutCursor_returnsAscendingBoundedRepositorySlice() {
        Transaction transaction = new Transaction();
        PageRequest pageRequest = PageRequest.of(0, TransactionService.DEFAULT_LIMIT);
        Slice<Transaction> expected = new SliceImpl<>(List.of(transaction), pageRequest, false);
        when(transactionRepository.findAllByOrderByIdAsc(pageRequest)).thenReturn(expected);

        Slice<Transaction> result = transactionService.findAll(
                null,
                TransactionService.DEFAULT_LIMIT
        );

        assertThat(result.getContent()).containsExactly(transaction);
        verify(transactionRepository).findAllByOrderByIdAsc(pageRequest);
    }

    @Test
    void findAll_withCursor_usesStrictlyGreaterThanAscendingRepositorySlice() {
        Transaction transaction = new Transaction();
        PageRequest pageRequest = PageRequest.of(0, 2);
        Slice<Transaction> expected = new SliceImpl<>(List.of(transaction), pageRequest, true);
        when(transactionRepository.findByIdGreaterThanOrderByIdAsc(7L, pageRequest))
                .thenReturn(expected);

        Slice<Transaction> result = transactionService.findAll(7L, 2);

        assertThat(result).isSameAs(expected);
        verify(transactionRepository).findByIdGreaterThanOrderByIdAsc(7L, pageRequest);
    }

    @Test
    void findAll_rejectsInvalidCursorOrLimitBeforeRepositoryAccess() {
        assertThatThrownBy(() -> transactionService.findAll(0L, TransactionService.DEFAULT_LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transactionService.findAll(1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transactionService.findAll(1L, TransactionService.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionRepository);
    }

    @Test
    void findByAccount_queriesBothSidesOfAccountHistory() {
        Transaction transaction = new Transaction();
        when(transactionRepository.findByFromAccountOrToAccount(7L, 7L))
                .thenReturn(List.of(transaction));

        List<Transaction> result = transactionService.findByAccount(7L);

        assertThat(result).containsExactly(transaction);
        verify(transactionRepository).findByFromAccountOrToAccount(7L, 7L);
    }

    private Transaction completedTransaction(
            Long paymentId,
            Long fromAccount,
            Long toAccount,
            String amount
    ) {
        Transaction transaction = new Transaction();
        transaction.setId(99L);
        transaction.setPaymentId(paymentId);
        transaction.setFromAccount(fromAccount);
        transaction.setToAccount(toAccount);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setStatus("COMPLETED");
        return transaction;
    }
}
