package com.banking.payment.service;

public class PaymentOutboxRecoveryCommandConflictException extends RuntimeException {
    public PaymentOutboxRecoveryCommandConflictException() {
        super("Recovery command key is already assigned to another request");
    }
}
