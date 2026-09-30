package com.banking.account.service;

import com.banking.account.api.CreateAccountRequest;
import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Slice<Account> findAll(Long afterId, int limit) {
        validatePageRequest(afterId, limit);
        PageRequest pageRequest = PageRequest.of(0, limit);
        if (afterId == null) {
            return accountRepository.findAllByOrderByIdAsc(pageRequest);
        }
        return accountRepository.findByIdGreaterThanOrderByIdAsc(afterId, pageRequest);
    }

    public Account create(CreateAccountRequest request) {
        Account account = new Account(request.customerName(), request.balance());
        return accountRepository.save(account);
    }

    private void validatePageRequest(Long afterId, int limit) {
        if (afterId != null && afterId <= 0) {
            throw new IllegalArgumentException("account cursor must be positive");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "account list limit must be between 1 and " + MAX_LIMIT
            );
        }
    }
}
