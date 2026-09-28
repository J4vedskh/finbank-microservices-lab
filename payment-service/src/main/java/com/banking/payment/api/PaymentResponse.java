package com.banking.payment.api;

import com.banking.payment.entity.Payment;

import java.math.BigDecimal;

public record PaymentResponse(
        Long id,
        Long fromAccount,
        Long toAccount,
        BigDecimal amount,
        String status
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getFromAccount(),
                payment.getToAccount(),
                payment.getAmount(),
                payment.getStatus()
        );
    }
}
