package com.banking.transaction.repository;

import com.banking.transaction.entity.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TransactionRepositoryPersistenceTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void save_generatesIdentityAndSupportsBothSidesOfAccountHistory() {
        Transaction transaction = new Transaction();
        transaction.setFromAccount(1L);
        transaction.setToAccount(2L);
        transaction.setAmount(new BigDecimal("750.00"));
        transaction.setStatus("COMPLETED");

        Transaction saved = transactionRepository.saveAndFlush(transaction);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        entityManager.clear();

        List<Transaction> sourceHistory =
                transactionRepository.findByFromAccountOrToAccount(1L, 1L);
        List<Transaction> destinationHistory =
                transactionRepository.findByFromAccountOrToAccount(2L, 2L);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(sourceHistory).singleElement().satisfies(this::assertPersistedTransaction);
        assertThat(destinationHistory).singleElement().satisfies(this::assertPersistedTransaction);
    }

    private void assertPersistedTransaction(Transaction transaction) {
        assertThat(transaction.getFromAccount()).isEqualTo(1L);
        assertThat(transaction.getToAccount()).isEqualTo(2L);
        assertThat(transaction.getAmount()).isEqualByComparingTo("750.00");
        assertThat(transaction.getStatus()).isEqualTo("COMPLETED");
        assertThat(transaction.getCreatedAt()).isNotNull();
    }
}
