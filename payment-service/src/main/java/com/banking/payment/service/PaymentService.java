package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PaymentService {
    private static final String PAYMENT_TOPIC = "payments";

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public PaymentService(
            PaymentRepository paymentRepository,
            KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.paymentRepository = paymentRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public List<Payment> findAll() {
        return paymentRepository.findAll();
    }

    public Payment create(CreatePaymentRequest request) {
        Payment payment = new Payment();
        payment.setFromAccount(request.fromAccount());
        payment.setToAccount(request.toAccount());
        payment.setAmount(request.amount());
        payment.setStatus("CREATED");

        Payment saved = paymentRepository.save(payment);
        // KafkaTemplate sends asynchronously; delivery recovery is a separate resilience increment.
        kafkaTemplate.send(PAYMENT_TOPIC, toPaymentEvent(saved));
        return saved;
    }

    private String toPaymentEvent(Payment payment) {
        return payment.getId()
                + "|" + payment.getFromAccount()
                + "|" + payment.getToAccount()
                + "|" + payment.getAmount();
    }
}
