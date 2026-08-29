package com.banking.account.service;

import com.banking.account.api.CreateAccountRequest;
import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void create_mapsClientInputWithoutAcceptingAnId() {
        CreateAccountRequest request = new CreateAccountRequest("Asha Mehta", new BigDecimal("5000.00"));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            assertThat(account.getId()).isNull();
            account.setId(42L);
            return account;
        });

        Account result = accountService.create(request);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account persisted = accountCaptor.getValue();
        assertThat(persisted.getCustomerName()).isEqualTo("Asha Mehta");
        assertThat(persisted.getBalance()).isEqualByComparingTo("5000.00");
        assertThat(result).isSameAs(persisted);
        assertThat(result.getId()).isEqualTo(42L);
    }

    @Test
    void findAll_returnsRepositoryResults() {
        Account account = new Account("Asha Mehta", new BigDecimal("5000.00"));
        when(accountRepository.findAll()).thenReturn(List.of(account));

        List<Account> result = accountService.findAll();

        assertThat(result).containsExactly(account);
    }
}
