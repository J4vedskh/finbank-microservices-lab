package com.banking.transaction.messaging;

import com.banking.transaction.service.TransactionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {
    private final TransactionService transactionService;

    public PaymentEventListener(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @KafkaListener(topics = "payments", groupId = "transaction-group")
    public void consume(String payload) {
        transactionService.recordPaymentEvent(payload);
    }
}
