package com.banking.payment.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "payment_outbox_recovery_audit",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_outbox_recovery_key_hash",
                columnNames = "recovery_key_hash"
        ),
        indexes = @Index(
                name = "idx_payment_outbox_recovery_event_time",
                columnList = "outbox_event_id,requeued_at"
        )
)
public class PaymentOutboxRecoveryAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "outbox_event_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_outbox_recovery_event")
    )
    private PaymentOutboxEvent outboxEvent;

    @Column(name = "recovery_key_hash", nullable = false, updatable = false, length = 64)
    private String recoveryKeyHash;

    @Column(nullable = false, updatable = false, length = 100)
    private String actor;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(name = "requeued_at", nullable = false, updatable = false)
    private Instant requeuedAt;

    @Column(name = "previous_exhausted_at", nullable = false, updatable = false)
    private Instant previousExhaustedAt;

    @Column(name = "previous_attempt_count", nullable = false, updatable = false)
    private int previousAttemptCount;

    @Column(name = "previous_last_error", updatable = false, length = 512)
    private String previousLastError;

    protected PaymentOutboxRecoveryAudit() {
    }

    public PaymentOutboxRecoveryAudit(
            PaymentOutboxEvent outboxEvent,
            String recoveryKeyHash,
            String actor,
            String reason,
            Instant requeuedAt,
            Instant previousExhaustedAt,
            int previousAttemptCount,
            String previousLastError
    ) {
        this.outboxEvent = Objects.requireNonNull(outboxEvent);
        this.recoveryKeyHash = Objects.requireNonNull(recoveryKeyHash);
        this.actor = Objects.requireNonNull(actor);
        this.reason = Objects.requireNonNull(reason);
        this.requeuedAt = Objects.requireNonNull(requeuedAt);
        this.previousExhaustedAt = Objects.requireNonNull(previousExhaustedAt);
        this.previousAttemptCount = previousAttemptCount;
        this.previousLastError = previousLastError;
    }

    public boolean matches(Long eventId, String expectedActor, String expectedReason) {
        return Objects.equals(outboxEvent.getId(), eventId)
                && actor.equals(expectedActor)
                && reason.equals(expectedReason);
    }

    public Long getId() {
        return id;
    }

    @JsonIgnore
    public PaymentOutboxEvent getOutboxEvent() {
        return outboxEvent;
    }

    @JsonIgnore
    public String getRecoveryKeyHash() {
        return recoveryKeyHash;
    }

    public String getActor() {
        return actor;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRequeuedAt() {
        return requeuedAt;
    }

    public Instant getPreviousExhaustedAt() {
        return previousExhaustedAt;
    }

    public int getPreviousAttemptCount() {
        return previousAttemptCount;
    }

    public String getPreviousLastError() {
        return previousLastError;
    }
}
