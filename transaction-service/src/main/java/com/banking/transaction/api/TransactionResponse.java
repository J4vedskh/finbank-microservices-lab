package com.banking.transaction.api;

import com.banking.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        Long id,
        Long paymentId,
        Long fromAccount,
        Long toAccount,
        BigDecimal amount,
        Instant createdAt,
        String status
) {
    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getPaymentId(),
                transaction.getFromAccount(),
                transaction.getToAccount(),
                transaction.getAmount(),
                transaction.getCreatedAt(),
                transaction.getStatus()
        );
    }
}
