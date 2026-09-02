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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void recordPaymentEvent_validPayload_persistsCompletedTransaction() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            transaction.setId(99L);
            return transaction;
        });

        Transaction result = transactionService.recordPaymentEvent("42|1|2|750.00");

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction persisted = transactionCaptor.getValue();
        assertThat(persisted.getId()).isEqualTo(99L);
        assertThat(persisted.getFromAccount()).isEqualTo(1L);
        assertThat(persisted.getToAccount()).isEqualTo(2L);
        assertThat(persisted.getAmount()).isEqualByComparingTo("750.00");
        assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(result).isSameAs(persisted);
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
            "42|1|2|-0.01"
    })
    void recordPaymentEvent_malformedPayload_failsBeforePersistence(String payload) {
        assertThatThrownBy(() -> transactionService.recordPaymentEvent(payload))
                .isInstanceOf(MalformedPaymentEventException.class)
                .hasMessageStartingWith("Invalid payment event:");

        verifyNoInteractions(transactionRepository);
    }

    @Test
    void recordPaymentEvent_repositoryFailure_propagatesUnchanged() {
        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> transactionService.recordPaymentEvent("42|1|2|750.00"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void findAll_returnsRepositoryResults() {
        Transaction transaction = new Transaction();
        when(transactionRepository.findAll()).thenReturn(List.of(transaction));

        List<Transaction> result = transactionService.findAll();

        assertThat(result).containsExactly(transaction);
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
}
