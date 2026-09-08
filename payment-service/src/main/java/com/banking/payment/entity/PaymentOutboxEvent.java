package com.banking.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "payment_outbox_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_outbox_payment_id",
                columnNames = "payment_id"
        ),
        indexes = @Index(
                name = "idx_payment_outbox_due",
                columnList = "status,exhausted_at,next_attempt_at,created_at"
        )
)
public class PaymentOutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "payment_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_payment_outbox_payment")
    )
    private Payment payment;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;

    @Column(nullable = false, length = 512)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "exhausted_at")
    private Instant exhaustedAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Version
    private Long version;

    protected PaymentOutboxEvent() {
    }

    public PaymentOutboxEvent(
            Payment payment,
            String topic,
            String eventKey,
            String payload,
            Instant createdAt
    ) {
        this.payment = payment;
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
        this.status = PaymentOutboxStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public void markPublished(Instant publishedAt) {
        this.status = PaymentOutboxStatus.PUBLISHED;
        this.attemptCount++;
        this.publishedAt = publishedAt;
        this.exhaustedAt = null;
        this.lastError = null;
    }

    public void scheduleRetry(Instant nextAttemptAt, String errorType) {
        this.status = PaymentOutboxStatus.PENDING;
        this.attemptCount++;
        this.nextAttemptAt = nextAttemptAt;
        this.publishedAt = null;
        this.exhaustedAt = null;
        this.lastError = errorType;
    }

    public void markExhausted(Instant exhaustedAt, String errorType) {
        this.status = PaymentOutboxStatus.PENDING;
        this.attemptCount++;
        this.publishedAt = null;
        this.exhaustedAt = exhaustedAt;
        this.lastError = errorType;
    }

    public void markExhaustedWithoutAttempt(Instant exhaustedAt) {
        this.status = PaymentOutboxStatus.PENDING;
        this.publishedAt = null;
        this.exhaustedAt = exhaustedAt;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public String getTopic() {
        return topic;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getPayload() {
        return payload;
    }

    public PaymentOutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getExhaustedAt() {
        return exhaustedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Long getVersion() {
        return version;
    }
}
