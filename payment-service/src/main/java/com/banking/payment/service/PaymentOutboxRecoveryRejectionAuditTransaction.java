package com.banking.payment.service;

import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionCode;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

@Service
public class PaymentOutboxRecoveryRejectionAuditTransaction {
    private final PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository;
    private final Clock clock;

    @Autowired
    public PaymentOutboxRecoveryRejectionAuditTransaction(
            PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository
    ) {
        this(rejectionAuditRepository, Clock.systemUTC());
    }

    PaymentOutboxRecoveryRejectionAuditTransaction(
            PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository,
            Clock clock
    ) {
        this.rejectionAuditRepository = rejectionAuditRepository;
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentOutboxRecoveryRejectionAudit record(
            Long requestedEventId,
            PaymentOutboxRecoveryRejectionCode rejectionCode
    ) {
        PaymentOutboxRecoveryRejectionAudit audit = new PaymentOutboxRecoveryRejectionAudit(
                requestedEventId,
                rejectionCode,
                clock.instant()
        );
        try {
            return rejectionAuditRepository.saveAndFlush(audit);
        } catch (DataAccessException failure) {
            throw new PaymentOutboxRecoveryRejectionAuditUnavailableException(failure);
        }
    }
}
