package com.banking.payment.entity;

public enum PaymentOutboxRecoveryRejectionCode {
    EVENT_NOT_FOUND,
    EVENT_NOT_ELIGIBLE,
    COMMAND_CONFLICT
}
