package com.banking.payment.repository;

import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentOutboxRecoveryAuditRepository
        extends JpaRepository<PaymentOutboxRecoveryAudit, Long> {

    @Query("""
            select audit
            from PaymentOutboxRecoveryAudit audit
            join fetch audit.outboxEvent
            where audit.recoveryKeyHash = :recoveryKeyHash
            """)
    Optional<PaymentOutboxRecoveryAudit> findByRecoveryKeyHash(
            @Param("recoveryKeyHash") String recoveryKeyHash
    );
}
