package com.banking.payment.service;

public class PaymentOutboxEventNotFoundException extends RuntimeException {
    public PaymentOutboxEventNotFoundException(Long eventId) {
        super("Payment outbox event " + eventId + " was not found");
    }
}
