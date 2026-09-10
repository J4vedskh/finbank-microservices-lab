package com.banking.payment.service;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class PaymentOutboxRecoveryTransaction {
    private static final String EXHAUSTED_PAYMENT_STATUS = "PUBLISH_EXHAUSTED";

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final PaymentOutboxRecoveryAuditRepository auditRepository;
    private final Clock clock;

    @Autowired
    public PaymentOutboxRecoveryTransaction(
            PaymentOutboxRepository paymentOutboxRepository,
            PaymentOutboxRecoveryAuditRepository auditRepository
    ) {
        this(paymentOutboxRepository, auditRepository, Clock.systemUTC());
    }

    PaymentOutboxRecoveryTransaction(
            PaymentOutboxRepository paymentOutboxRepository,
            PaymentOutboxRecoveryAuditRepository auditRepository,
            Clock clock
    ) {
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.auditRepository = auditRepository;
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentOutboxRecoveryAudit requeueExhausted(
            Long eventId,
            String recoveryKeyHash,
            String actor,
            String reason
    ) {
        PaymentOutboxEvent event = paymentOutboxRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new PaymentOutboxEventNotFoundException(eventId));

        PaymentOutboxRecoveryAudit existing = auditRepository
                .findByRecoveryKeyHash(recoveryKeyHash)
                .orElse(null);
        if (existing != null) {
            return PaymentOutboxRecoveryService.requireMatching(
                    existing,
                    eventId,
                    actor,
                    reason
            );
        }

        if (!isRecoverable(event)) {
            throw new PaymentOutboxRecoveryNotAllowedException(eventId);
        }

        Instant requeuedAt = clock.instant();
        PaymentOutboxRecoveryAudit audit = new PaymentOutboxRecoveryAudit(
                event,
                recoveryKeyHash,
                actor,
                reason,
                requeuedAt,
                event.getExhaustedAt(),
                event.getAttemptCount(),
                event.getLastError()
        );
        event.requeue(requeuedAt);
        event.getPayment().setStatus("PENDING_RETRY");
        paymentOutboxRepository.flush();
        return auditRepository.saveAndFlush(audit);
    }

    private boolean isRecoverable(PaymentOutboxEvent event) {
        return event.getStatus() == PaymentOutboxStatus.PENDING
                && event.getExhaustedAt() != null
                && EXHAUSTED_PAYMENT_STATUS.equals(event.getPayment().getStatus());
    }
}
