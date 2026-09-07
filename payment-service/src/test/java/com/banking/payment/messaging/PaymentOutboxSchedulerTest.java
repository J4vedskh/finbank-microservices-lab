package com.banking.payment.messaging;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentOutboxSchedulerTest {

    @Test
    void publishBatch_stopsWhenNoMoreEventsAreDue() {
        PaymentOutboxPublisher publisher = mock(PaymentOutboxPublisher.class);
        PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(publisher, 20);
        when(publisher.publishNext()).thenReturn(true, true, false);

        scheduler.publishBatch();

        verify(publisher, times(3)).publishNext();
    }

    @Test
    void publishBatch_respectsConfiguredBatchLimit() {
        PaymentOutboxPublisher publisher = mock(PaymentOutboxPublisher.class);
        PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(publisher, 2);
        when(publisher.publishNext()).thenReturn(true);

        scheduler.publishBatch();

        verify(publisher, times(2)).publishNext();
    }

    @Test
    void publishBatch_interruptedThread_stopsBeforePublishing() {
        PaymentOutboxPublisher publisher = mock(PaymentOutboxPublisher.class);
        PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(publisher, 20);

        Thread.currentThread().interrupt();
        try {
            scheduler.publishBatch();
        } finally {
            Thread.interrupted();
        }

        verifyNoInteractions(publisher);
    }
}
