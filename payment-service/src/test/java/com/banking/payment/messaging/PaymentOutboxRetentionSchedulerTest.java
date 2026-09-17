package com.banking.payment.messaging;

import com.banking.payment.service.PaymentOutboxRetentionResult;
import com.banking.payment.service.PaymentOutboxRetentionService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PaymentOutboxRetentionSchedulerTest {

    @Test
    void purgeExpired_delegatesExactlyOneBoundedRun() {
        PaymentOutboxRetentionService service = mock(PaymentOutboxRetentionService.class);
        when(service.runOnce()).thenReturn(new PaymentOutboxRetentionResult(1, 2, 3, 4));
        PaymentOutboxRetentionScheduler scheduler =
                new PaymentOutboxRetentionScheduler(service);

        scheduler.purgeExpired();

        verify(service).runOnce();
        verifyNoMoreInteractions(service);
    }
}
