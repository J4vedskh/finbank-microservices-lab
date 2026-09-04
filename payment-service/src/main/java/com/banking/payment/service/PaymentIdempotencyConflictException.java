package com.banking.payment.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PaymentIdempotencyConflictException extends IllegalStateException {
    public PaymentIdempotencyConflictException() {
        super("Idempotency key is already associated with a different payment");
    }
}
