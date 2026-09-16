package com.banking.payment.service;

public record PaymentOutboxRetentionResult(
        int publishedEventsDeleted,
        int recoveryAuditsDeleted,
        int rejectionAuditsDeleted
) {
    public int totalDeleted() {
        return publishedEventsDeleted + recoveryAuditsDeleted + rejectionAuditsDeleted;
    }
}
