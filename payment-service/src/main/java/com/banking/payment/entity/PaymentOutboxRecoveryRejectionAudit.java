package com.banking.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "payment_outbox_recovery_rejection_audit",
        indexes = @Index(
                name = "idx_payment_outbox_rejection_event_time",
                columnList = "requested_event_id,rejected_at"
        )
)
public class PaymentOutboxRecoveryRejectionAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_event_id", nullable = false, updatable = false)
    private Long requestedEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_code", nullable = false, updatable = false, length = 32)
    private PaymentOutboxRecoveryRejectionCode rejectionCode;

    @Column(name = "rejected_at", nullable = false, updatable = false)
    private Instant rejectedAt;

    protected PaymentOutboxRecoveryRejectionAudit() {
    }

    public PaymentOutboxRecoveryRejectionAudit(
            Long requestedEventId,
            PaymentOutboxRecoveryRejectionCode rejectionCode,
            Instant rejectedAt
    ) {
        this.requestedEventId = Objects.requireNonNull(requestedEventId);
        this.rejectionCode = Objects.requireNonNull(rejectionCode);
        this.rejectedAt = Objects.requireNonNull(rejectedAt);
    }

    public Long getId() {
        return id;
    }

    public Long getRequestedEventId() {
        return requestedEventId;
    }

    public PaymentOutboxRecoveryRejectionCode getRejectionCode() {
        return rejectionCode;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }
}
