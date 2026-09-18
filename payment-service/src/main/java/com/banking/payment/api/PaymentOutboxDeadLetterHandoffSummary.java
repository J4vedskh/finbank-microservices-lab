package com.banking.payment.api;

import java.time.Instant;

public record PaymentOutboxDeadLetterHandoffSummary(
        Long handoffId,
        Long eventId,
        int exhaustionSequence,
        Instant exhaustedAt,
        int attemptCount,
        String failureType
) {
}
