package com.banking.transaction.service;

public class PaymentEventConflictException extends IllegalStateException {
    public PaymentEventConflictException(long paymentId) {
        super("Payment event " + paymentId + " conflicts with its existing ledger entry");
    }
}
