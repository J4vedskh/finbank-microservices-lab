package com.banking.transaction.service;

public class MalformedPaymentEventException extends IllegalArgumentException {
    public MalformedPaymentEventException(String reason) {
        super("Invalid payment event: " + reason);
    }

    public MalformedPaymentEventException(String reason, Throwable cause) {
        super("Invalid payment event: " + reason, cause);
    }
}
