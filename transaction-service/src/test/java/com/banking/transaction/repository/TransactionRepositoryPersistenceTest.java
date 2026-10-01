package com.banking.transaction.repository;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TransactionService.class)
class TransactionRepositoryPersistenceTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TransactionService transactionService;

    @Test
    void save_generatesIdentityAndSupportsBothSidesOfAccountHistory() {
        Transaction transaction = new Transaction();
        transaction.setPaymentId(42L);
        transaction.setFromAccount(1L);
        transaction.setToAccount(2L);
        transaction.setAmount(new BigDecimal("750.00"));
        transaction.setStatus("COMPLETED");

        Transaction saved = transactionRepository.saveAndFlush(transaction);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        entityManager.clear();

        PageRequest pageRequest = PageRequest.of(0, TransactionService.DEFAULT_LIMIT);
        Slice<Transaction> sourceHistory =
                transactionRepository.findAccountHistory(1L, pageRequest);
        Slice<Transaction> destinationHistory =
                transactionRepository.findAccountHistory(2L, pageRequest);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(sourceHistory.getContent()).singleElement()
                .satisfies(this::assertPersistedTransaction);
        assertThat(destinationHistory.getContent()).singleElement()
                .satisfies(this::assertPersistedTransaction);
    }

    @Test
    void recordPaymentEvent_identicalReplayKeepsOneLedgerEntry() {
        Transaction first = transactionService.recordPaymentEvent("42|1|2|750.000");
        entityManager.flush();
        entityManager.clear();

        Transaction replay = transactionService.recordPaymentEvent("42|1|2|750.000");

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getPaymentId()).isEqualTo(42L);
        assertThat(replay.getAmount()).isEqualByComparingTo("750.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void save_duplicatePaymentId_isRejectedByDatabase() {
        transactionRepository.saveAndFlush(transaction(42L, 1L, 2L));

        assertThatThrownBy(() ->
                transactionRepository.saveAndFlush(transaction(42L, 3L, 4L))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void keysetPagination_isAscendingExclusiveAndDoesNotDuplicateRowsAcrossPages() {
        Transaction first = transactionRepository.saveAndFlush(transaction(101L, 1L, 2L));
        Transaction second = transactionRepository.saveAndFlush(transaction(102L, 3L, 4L));
        Transaction third = transactionRepository.saveAndFlush(transaction(103L, 5L, 6L));
        entityManager.clear();

        PageRequest pageRequest = PageRequest.of(0, 2);
        Slice<Transaction> firstPage = transactionRepository.findAllByOrderByIdAsc(pageRequest);
        Slice<Transaction> secondPage = transactionRepository
                .findByIdGreaterThanOrderByIdAsc(second.getId(), pageRequest);

        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.getContent()).extracting(Transaction::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.getContent()).extracting(Transaction::getId)
                .containsExactly(third.getId());
    }

    @Test
    void accountHistoryKeysetPagination_isAscendingExclusiveAndDoesNotDuplicateRowsAcrossPages() {
        Transaction sourceFirst = transactionRepository.saveAndFlush(transaction(201L, 7L, 1L));
        transactionRepository.saveAndFlush(transaction(202L, 8L, 9L));
        Transaction destinationSecond = transactionRepository.saveAndFlush(transaction(203L, 2L, 7L));
        Transaction sourceThird = transactionRepository.saveAndFlush(transaction(204L, 7L, 3L));
        transactionRepository.saveAndFlush(transaction(205L, 4L, 5L));
        entityManager.clear();

        PageRequest pageRequest = PageRequest.of(0, 2);
        Slice<Transaction> firstPage = transactionRepository.findAccountHistory(7L, pageRequest);
        Slice<Transaction> secondPage = transactionRepository.findAccountHistoryAfterId(
                7L,
                destinationSecond.getId(),
                pageRequest
        );

        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.getContent()).extracting(Transaction::getId)
                .containsExactly(sourceFirst.getId(), destinationSecond.getId());
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.getContent()).extracting(Transaction::getId)
                .containsExactly(sourceThird.getId());
        assertThat(Stream.concat(
                firstPage.getContent().stream(),
                secondPage.getContent().stream()
        ).map(Transaction::getId).toList())
                .containsExactly(
                        sourceFirst.getId(),
                        destinationSecond.getId(),
                        sourceThird.getId()
                )
                .doesNotHaveDuplicates();
    }

    private void assertPersistedTransaction(Transaction transaction) {
        assertThat(transaction.getPaymentId()).isEqualTo(42L);
        assertThat(transaction.getFromAccount()).isEqualTo(1L);
        assertThat(transaction.getToAccount()).isEqualTo(2L);
        assertThat(transaction.getAmount()).isEqualByComparingTo("750.00");
        assertThat(transaction.getStatus()).isEqualTo("COMPLETED");
        assertThat(transaction.getCreatedAt()).isNotNull();
    }

    private Transaction transaction(Long paymentId, Long fromAccount, Long toAccount) {
        Transaction transaction = new Transaction();
        transaction.setPaymentId(paymentId);
        transaction.setFromAccount(fromAccount);
        transaction.setToAccount(toAccount);
        transaction.setAmount(new BigDecimal("750.00"));
        transaction.setStatus("COMPLETED");
        return transaction;
    }
}
