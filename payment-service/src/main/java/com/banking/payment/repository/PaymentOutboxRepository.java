package com.banking.payment.repository;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Optional;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentOutboxEvent>
    findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
            PaymentOutboxStatus status,
            Instant now
    );
}
