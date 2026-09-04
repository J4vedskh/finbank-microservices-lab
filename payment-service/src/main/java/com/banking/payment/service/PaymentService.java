package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

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

    public Payment create(String idempotencyKey, CreatePaymentRequest request) {
        String idempotencyKeyHash = hash(idempotencyKey);

        return paymentRepository.findByIdempotencyKeyHash(idempotencyKeyHash)
                .map(existing -> acceptReplay(existing, request))
                .orElseGet(() -> persistAndPublish(idempotencyKeyHash, request));
    }

    private Payment persistAndPublish(
            String idempotencyKeyHash,
            CreatePaymentRequest request
    ) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(idempotencyKeyHash);
        payment.setFromAccount(request.fromAccount());
        payment.setToAccount(request.toAccount());
        payment.setAmount(request.amount());
        payment.setStatus("CREATED");

        Payment saved;
        try {
            saved = paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException exception) {
            return paymentRepository.findByIdempotencyKeyHash(idempotencyKeyHash)
                    .map(existing -> acceptReplay(existing, request))
                    .orElseThrow(() -> exception);
        }

        // KafkaTemplate sends asynchronously; delivery recovery is a separate resilience increment.
        kafkaTemplate.send(PAYMENT_TOPIC, toPaymentEvent(saved));
        return saved;
    }

    private Payment acceptReplay(Payment existing, CreatePaymentRequest request) {
        if (sameRequest(existing, request)) {
            return existing;
        }
        throw new PaymentIdempotencyConflictException();
    }

    private boolean sameRequest(Payment existing, CreatePaymentRequest request) {
        return Objects.equals(existing.getFromAccount(), request.fromAccount())
                && Objects.equals(existing.getToAccount(), request.toAccount())
                && existing.getAmount() != null
                && existing.getAmount().compareTo(request.amount()) == 0;
    }

    private String hash(String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(idempotencyKey.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String toPaymentEvent(Payment payment) {
        return payment.getId()
                + "|" + payment.getFromAccount()
                + "|" + payment.getToAccount()
                + "|" + payment.getAmount();
    }
}
