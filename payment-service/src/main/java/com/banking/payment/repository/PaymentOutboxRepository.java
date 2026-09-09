package com.banking.payment.repository;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentOutboxEvent>
    findFirstByStatusAndExhaustedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
            PaymentOutboxStatus status,
            Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event
            from PaymentOutboxEvent event
            join fetch event.payment
            where event.id = :eventId
            """)
    Optional<PaymentOutboxEvent> findByIdForUpdate(@Param("eventId") Long eventId);
}
