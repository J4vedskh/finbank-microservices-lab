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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void findAll_usesExclusiveCursorAndBoundedSlice() {
        Account account = new Account("Asha Mehta", new BigDecimal("5000.00"));
        account.setId(42L);
        PageRequest pageRequest = PageRequest.of(0, 2);
        Slice<Account> expected = new SliceImpl<>(List.of(account), pageRequest, true);
        when(accountRepository.findByIdGreaterThanOrderByIdAsc(40L, pageRequest))
                .thenReturn(expected);

        Slice<Account> result = accountService.findAll(40L, 2);

        assertThat(result.getContent()).containsExactly(account);
        assertThat(result.hasNext()).isTrue();
        verify(accountRepository).findByIdGreaterThanOrderByIdAsc(40L, pageRequest);
    }

    @Test
    void findAll_rejectsInvalidCursorAndLimitBeforeRepositoryAccess() {
        assertThatThrownBy(() -> accountService.findAll(0L, AccountService.DEFAULT_LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accountService.findAll(-1L, AccountService.DEFAULT_LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accountService.findAll(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accountService.findAll(null, AccountService.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountRepository);
    }
}
