package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PaymentCreationTransaction {
    private static final String PAYMENT_TOPIC = "payments";

    private final PaymentRepository paymentRepository;
    private final PaymentOutboxRepository paymentOutboxRepository;

    public PaymentCreationTransaction(
            PaymentRepository paymentRepository,
            PaymentOutboxRepository paymentOutboxRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentOutboxRepository = paymentOutboxRepository;
    }

    @Transactional
    public Payment create(
            String idempotencyKeyHash,
            CreatePaymentRequest request
    ) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(idempotencyKeyHash);
        payment.setFromAccount(request.fromAccount());
        payment.setToAccount(request.toAccount());
        payment.setAmount(request.amount());
        payment.setStatus("CREATED");

        Payment saved = paymentRepository.saveAndFlush(payment);
        Instant createdAt = Instant.now();
        PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(
                saved,
                PAYMENT_TOPIC,
                saved.getId().toString(),
                toPaymentEvent(saved),
                createdAt
        );
        paymentOutboxRepository.saveAndFlush(outboxEvent);
        return saved;
    }

    private String toPaymentEvent(Payment payment) {
        return payment.getId()
                + "|" + payment.getFromAccount()
                + "|" + payment.getToAccount()
                + "|" + payment.getAmount();
    }
}
