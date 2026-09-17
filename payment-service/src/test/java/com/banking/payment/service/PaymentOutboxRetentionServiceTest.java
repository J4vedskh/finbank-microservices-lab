package com.banking.payment.service;

import com.banking.payment.config.PaymentOutboxRetentionProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOutboxRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-16T05:30:00Z");

    @Test
    void runOnce_usesOneClockReadingAndIndependentRetentionWindows() {
        PaymentOutboxRetentionTransaction transaction =
                mock(PaymentOutboxRetentionTransaction.class);
        PaymentOutboxRetentionProperties properties =
                new PaymentOutboxRetentionProperties(false, 30, 10, 25, 60_000);
        PaymentOutboxRetentionResult expected =
                new PaymentOutboxRetentionResult(2, 3, 4, 5);
        when(transaction.purgeExpired(
                Instant.parse("2026-08-17T05:30:00Z"),
                Instant.parse("2026-09-06T05:30:00Z"),
                25
        )).thenReturn(expected);
        PaymentOutboxRetentionService service = new PaymentOutboxRetentionService(
                transaction,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        PaymentOutboxRetentionResult result = service.runOnce();

        assertThat(result).isSameAs(expected);
        verify(transaction).purgeExpired(
                Instant.parse("2026-08-17T05:30:00Z"),
                Instant.parse("2026-09-06T05:30:00Z"),
                25
        );
    }
}
