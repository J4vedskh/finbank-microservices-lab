package com.banking.account.api;

import com.banking.account.entity.Account;

import java.math.BigDecimal;

public record AccountResponse(
        Long id,
        String customerName,
        BigDecimal balance
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCustomerName(),
                account.getBalance()
        );
    }
}
