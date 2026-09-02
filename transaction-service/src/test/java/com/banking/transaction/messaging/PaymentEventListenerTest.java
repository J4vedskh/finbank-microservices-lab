package com.banking.transaction.messaging;

import com.banking.transaction.service.MalformedPaymentEventException;
import com.banking.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock
    private TransactionService transactionService;

    @InjectMocks
    private PaymentEventListener paymentEventListener;

    @Test
    void consume_delegatesRawPayloadToTransactionService() {
        String payload = "42|1|2|750.00";

        paymentEventListener.consume(payload);

        verify(transactionService).recordPaymentEvent(payload);
    }

    @Test
    void consume_malformedPayload_propagatesFailureToKafkaContainer() {
        String payload = "invalid";
        MalformedPaymentEventException failure =
                new MalformedPaymentEventException("expected paymentId|fromAccount|toAccount|amount");
        doThrow(failure).when(transactionService).recordPaymentEvent(payload);

        assertThatThrownBy(() -> paymentEventListener.consume(payload)).isSameAs(failure);
    }
}
