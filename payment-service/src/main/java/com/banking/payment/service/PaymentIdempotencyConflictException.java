package com.banking.payment.service;

public class PaymentIdempotencyConflictException extends IllegalStateException {
    public PaymentIdempotencyConflictException() {
        super("Idempotency key is already associated with a different payment");
    }
}
