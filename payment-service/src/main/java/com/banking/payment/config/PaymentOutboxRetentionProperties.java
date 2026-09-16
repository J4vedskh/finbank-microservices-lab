package com.banking.payment.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.outbox.retention")
public record PaymentOutboxRetentionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30") @Min(1) @Max(36500) int publishedRetentionDays,
        @DefaultValue("30") @Min(1) @Max(36500) int rejectionRetentionDays,
        @DefaultValue("100") @Min(1) @Max(1000) int batchSize,
        @DefaultValue("86400000") @Min(1000) long cleanupDelayMs
) {
}
