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
        name = "payment_outbox_dead_letter_handoff",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_outbox_dead_letter_cycle",
                columnNames = {"outbox_event_id", "exhaustion_sequence"}
        ),
        indexes = @Index(
                name = "idx_payment_outbox_dead_letter_event_time",
                columnList = "outbox_event_id,exhausted_at"
        )
)
public class PaymentOutboxDeadLetterHandoff {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "outbox_event_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_outbox_dead_letter_event")
    )
    private PaymentOutboxEvent outboxEvent;

    @Column(name = "exhaustion_sequence", nullable = false, updatable = false)
    private int exhaustionSequence;

    @Column(name = "exhausted_at", nullable = false, updatable = false)
    private Instant exhaustedAt;

    @Column(name = "attempt_count", nullable = false, updatable = false)
    private int attemptCount;

    @Column(name = "failure_type", updatable = false, length = 512)
    private String failureType;

    protected PaymentOutboxDeadLetterHandoff() {
    }

    public PaymentOutboxDeadLetterHandoff(PaymentOutboxEvent outboxEvent) {
        this.outboxEvent = Objects.requireNonNull(outboxEvent);
        this.exhaustionSequence = outboxEvent.getExhaustionSequence();
        this.exhaustedAt = Objects.requireNonNull(outboxEvent.getExhaustedAt());
        this.attemptCount = outboxEvent.getAttemptCount();
        this.failureType = outboxEvent.getLastError();
        if (exhaustionSequence <= 0) {
            throw new IllegalArgumentException("exhaustion sequence must be positive");
        }
    }

    public Long getId() {
        return id;
    }

    @JsonIgnore
    public PaymentOutboxEvent getOutboxEvent() {
        return outboxEvent;
    }

    public int getExhaustionSequence() {
        return exhaustionSequence;
    }

    public Instant getExhaustedAt() {
        return exhaustedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getFailureType() {
        return failureType;
    }
}
