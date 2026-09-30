package com.banking.account.service;

import com.banking.account.api.CreateAccountRequest;
import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Slice;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(AccountService.class)
class AccountServicePersistenceTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void create_persistsGeneratedIdentityAndSupportsListing() {
        Account created = accountService.create(
                new CreateAccountRequest("Asha Mehta", new BigDecimal("5000.00"))
        );

        assertThat(created.getId()).isNotNull();
        entityManager.flush();
        entityManager.clear();

        Slice<Account> accounts = accountService.findAll(null, 2);

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(accounts.getContent()).singleElement().satisfies(account -> {
            assertThat(account.getId()).isEqualTo(created.getId());
            assertThat(account.getCustomerName()).isEqualTo("Asha Mehta");
            assertThat(account.getBalance()).isEqualByComparingTo("5000.00");
        });
        assertThat(accounts.hasNext()).isFalse();
    }

    @Test
    void findAll_walksDeterministicKeysetPagesWithoutDuplicates() {
        Account first = accountService.create(accountRequest("Asha Mehta", "5000.00"));
        Account second = accountService.create(accountRequest("Dev Shah", "2500.00"));
        Account third = accountService.create(accountRequest("Ira Bose", "1000.00"));
        entityManager.flush();
        entityManager.clear();

        Slice<Account> firstPage = accountService.findAll(null, 2);
        Slice<Account> secondPage = accountService.findAll(
                firstPage.getContent().get(firstPage.getNumberOfElements() - 1).getId(),
                2
        );

        assertThat(firstPage.getContent())
                .extracting(Account::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.getContent())
                .extracting(Account::getId)
                .containsExactly(third.getId());
        assertThat(secondPage.hasNext()).isFalse();
    }

    private CreateAccountRequest accountRequest(String customerName, String balance) {
        return new CreateAccountRequest(customerName, new BigDecimal(balance));
    }
}
