package com.banking.payment.service;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class PaymentService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final PaymentRepository paymentRepository;
    private final PaymentCreationTransaction paymentCreationTransaction;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentCreationTransaction paymentCreationTransaction
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentCreationTransaction = paymentCreationTransaction;
    }

    @Transactional(readOnly = true)
    public Slice<Payment> findAll(Long afterId, int limit) {
        validatePageRequest(afterId, limit);
        PageRequest pageRequest = PageRequest.of(0, limit);
        if (afterId == null) {
            return paymentRepository.findAllByOrderByIdAsc(pageRequest);
        }
        return paymentRepository.findByIdGreaterThanOrderByIdAsc(afterId, pageRequest);
    }

    public Payment create(String idempotencyKey, CreatePaymentRequest request) {
        String idempotencyKeyHash = hash(idempotencyKey);

        return paymentRepository.findByIdempotencyKeyHash(idempotencyKeyHash)
                .map(existing -> acceptReplay(existing, request))
                .orElseGet(() -> createAtomically(idempotencyKeyHash, request));
    }

    private Payment createAtomically(
            String idempotencyKeyHash,
            CreatePaymentRequest request
    ) {
        try {
            return paymentCreationTransaction.create(idempotencyKeyHash, request);
        } catch (DataIntegrityViolationException exception) {
            return paymentRepository.findByIdempotencyKeyHash(idempotencyKeyHash)
                    .map(existing -> acceptReplay(existing, request))
                    .orElseThrow(() -> exception);
        }
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

    private void validatePageRequest(Long afterId, int limit) {
        if (afterId != null && afterId <= 0) {
            throw new IllegalArgumentException("payment cursor must be positive");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "payment list limit must be between 1 and " + MAX_LIMIT
            );
        }
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

}
