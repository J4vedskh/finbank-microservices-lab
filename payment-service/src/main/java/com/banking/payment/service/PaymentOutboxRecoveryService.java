package com.banking.payment.service;

import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionCode;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class PaymentOutboxRecoveryService {
    private static final int MIN_RECOVERY_KEY_LENGTH = 16;
    private static final int MAX_RECOVERY_KEY_LENGTH = 128;
    private static final int MAX_ACTOR_LENGTH = 100;
    private static final int MAX_REASON_LENGTH = 500;

    private final PaymentOutboxRecoveryAuditRepository auditRepository;
    private final PaymentOutboxRecoveryTransaction recoveryTransaction;
    private final PaymentOutboxRecoveryRejectionAuditTransaction rejectionAuditTransaction;

    public PaymentOutboxRecoveryService(
            PaymentOutboxRecoveryAuditRepository auditRepository,
            PaymentOutboxRecoveryTransaction recoveryTransaction,
            PaymentOutboxRecoveryRejectionAuditTransaction rejectionAuditTransaction
    ) {
        this.auditRepository = auditRepository;
        this.recoveryTransaction = recoveryTransaction;
        this.rejectionAuditTransaction = rejectionAuditTransaction;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentOutboxRecoveryAudit requeueExhausted(
            Long eventId,
            String recoveryKey,
            String actor,
            String reason
    ) {
        validateEventId(eventId);
        validateRecoveryKey(recoveryKey);
        String normalizedActor = normalizeRequired(actor, "actor", MAX_ACTOR_LENGTH);
        String normalizedReason = normalizeRequired(reason, "reason", MAX_REASON_LENGTH);
        String recoveryKeyHash = sha256(recoveryKey);

        try {
            return attemptRecovery(
                    eventId,
                    recoveryKeyHash,
                    normalizedActor,
                    normalizedReason
            );
        } catch (PaymentOutboxEventNotFoundException failure) {
            recordRejection(
                    eventId,
                    PaymentOutboxRecoveryRejectionCode.EVENT_NOT_FOUND
            );
            throw failure;
        } catch (PaymentOutboxRecoveryNotAllowedException failure) {
            recordRejection(
                    eventId,
                    PaymentOutboxRecoveryRejectionCode.EVENT_NOT_ELIGIBLE
            );
            throw failure;
        } catch (PaymentOutboxRecoveryCommandConflictException failure) {
            recordRejection(
                    eventId,
                    PaymentOutboxRecoveryRejectionCode.COMMAND_CONFLICT
            );
            throw failure;
        }
    }

    private void recordRejection(
            Long eventId,
            PaymentOutboxRecoveryRejectionCode rejectionCode
    ) {
        try {
            rejectionAuditTransaction.record(eventId, rejectionCode);
        } catch (PaymentOutboxRecoveryRejectionAuditUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PaymentOutboxRecoveryRejectionAuditUnavailableException(failure);
        }
    }

    private PaymentOutboxRecoveryAudit attemptRecovery(
            Long eventId,
            String recoveryKeyHash,
            String actor,
            String reason
    ) {
        PaymentOutboxRecoveryAudit existing = auditRepository
                .findByRecoveryKeyHash(recoveryKeyHash)
                .orElse(null);
        if (existing != null) {
            return requireMatching(existing, eventId, actor, reason);
        }

        try {
            return recoveryTransaction.requeueExhausted(
                    eventId,
                    recoveryKeyHash,
                    actor,
                    reason
            );
        } catch (DataIntegrityViolationException failure) {
            PaymentOutboxRecoveryAudit raced = auditRepository
                    .findByRecoveryKeyHash(recoveryKeyHash)
                    .orElseThrow(() -> failure);
            return requireMatching(raced, eventId, actor, reason);
        }
    }

    static PaymentOutboxRecoveryAudit requireMatching(
            PaymentOutboxRecoveryAudit audit,
            Long eventId,
            String actor,
            String reason
    ) {
        if (!audit.matches(eventId, actor, reason)) {
            throw new PaymentOutboxRecoveryCommandConflictException();
        }
        return audit;
    }

    private void validateEventId(Long eventId) {
        if (eventId == null || eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
    }

    private void validateRecoveryKey(String recoveryKey) {
        if (recoveryKey == null
                || recoveryKey.length() < MIN_RECOVERY_KEY_LENGTH
                || recoveryKey.length() > MAX_RECOVERY_KEY_LENGTH
                || recoveryKey.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalArgumentException(
                    "recoveryKey must contain 16 to 128 visible ASCII characters"
            );
        }
    }

    private String normalizeRequired(String value, String fieldName, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must contain 1 to " + maxLength + " characters"
            );
        }
        return normalized;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
