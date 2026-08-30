package com.banking.account.service;

import com.banking.account.api.CreateAccountRequest;
import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

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

        List<Account> accounts = accountService.findAll();

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.getId()).isEqualTo(created.getId());
            assertThat(account.getCustomerName()).isEqualTo("Asha Mehta");
            assertThat(account.getBalance()).isEqualByComparingTo("5000.00");
        });
    }
}
