package com.banking.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentOutboxRecoveryRequest(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
