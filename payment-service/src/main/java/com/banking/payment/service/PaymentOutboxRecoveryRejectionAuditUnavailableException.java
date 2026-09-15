package com.banking.payment.service;

public class PaymentOutboxRecoveryRejectionAuditUnavailableException extends RuntimeException {
    public PaymentOutboxRecoveryRejectionAuditUnavailableException(Throwable cause) {
        super("Recovery rejection audit is unavailable", cause);
    }
}
