package com.banking.payment.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotNull @Positive Long fromAccount,
        @NotNull @Positive Long toAccount,
        @NotNull @Positive @Digits(integer = 36, fraction = 2) BigDecimal amount
) {
    @AssertTrue(message = "source and destination accounts must be different")
    @JsonIgnore
    public boolean isAccountsDistinct() {
        return fromAccount == null || toAccount == null || !fromAccount.equals(toAccount);
    }
}
