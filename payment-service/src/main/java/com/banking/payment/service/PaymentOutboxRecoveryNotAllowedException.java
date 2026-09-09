package com.banking.payment.service;

public class PaymentOutboxRecoveryNotAllowedException extends IllegalStateException {
    public PaymentOutboxRecoveryNotAllowedException(Long eventId) {
        super("Payment outbox event " + eventId + " is not eligible for recovery");
    }
}
