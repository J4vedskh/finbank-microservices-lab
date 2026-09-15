package com.banking.payment.repository;

import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOutboxRecoveryRejectionAuditRepository
        extends JpaRepository<PaymentOutboxRecoveryRejectionAudit, Long> {
}
