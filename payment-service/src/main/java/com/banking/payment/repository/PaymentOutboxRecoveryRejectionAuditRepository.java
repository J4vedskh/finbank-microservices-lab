package com.banking.payment.repository;

import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;

public interface PaymentOutboxRecoveryRejectionAuditRepository
        extends JpaRepository<PaymentOutboxRecoveryRejectionAudit, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PaymentOutboxRecoveryRejectionAudit>
    findByRejectedAtLessThanOrderByRejectedAtAscIdAsc(
            Instant cutoff,
            Pageable pageable
    );
}
