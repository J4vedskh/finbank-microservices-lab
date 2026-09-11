package com.banking.payment.api;

import java.time.Instant;

public record PaymentOutboxRecoveryResponse(
        Long eventId,
        Instant requeuedAt
) {
}
