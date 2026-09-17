package com.banking.payment.service;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PaymentOutboxRetentionTransaction {
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final PaymentOutboxDeadLetterHandoffRepository deadLetterHandoffRepository;
    private final PaymentOutboxRecoveryAuditRepository recoveryAuditRepository;
    private final PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository;

    public PaymentOutboxRetentionTransaction(
            PaymentOutboxRepository paymentOutboxRepository,
            PaymentOutboxDeadLetterHandoffRepository deadLetterHandoffRepository,
            PaymentOutboxRecoveryAuditRepository recoveryAuditRepository,
            PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository
    ) {
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.deadLetterHandoffRepository = deadLetterHandoffRepository;
        this.recoveryAuditRepository = recoveryAuditRepository;
        this.rejectionAuditRepository = rejectionAuditRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentOutboxRetentionResult purgeExpired(
            Instant publishedCutoff,
            Instant rejectionCutoff,
            int batchSize
    ) {
        PageRequest batch = PageRequest.of(0, batchSize);
        List<PaymentOutboxEvent> publishedEvents = paymentOutboxRepository
                .findByStatusAndPublishedAtLessThanOrderByPublishedAtAscIdAsc(
                        PaymentOutboxStatus.PUBLISHED,
                        publishedCutoff,
                        batch
                );

        List<Long> publishedEventIds = publishedEvents.stream()
                .map(PaymentOutboxEvent::getId)
                .toList();
        int recoveryAuditsDeleted = 0;
        int deadLetterHandoffsDeleted = 0;
        if (!publishedEventIds.isEmpty()) {
            recoveryAuditsDeleted = recoveryAuditRepository
                    .deleteByOutboxEventIdIn(publishedEventIds);
            deadLetterHandoffsDeleted = deadLetterHandoffRepository
                    .deleteByOutboxEventIdIn(publishedEventIds);
            paymentOutboxRepository.deleteAllByIdInBatch(publishedEventIds);
        }

        List<PaymentOutboxRecoveryRejectionAudit> rejectionAudits = rejectionAuditRepository
                .findByRejectedAtLessThanOrderByRejectedAtAscIdAsc(
                        rejectionCutoff,
                        batch
                );
        List<Long> rejectionAuditIds = rejectionAudits.stream()
                .map(PaymentOutboxRecoveryRejectionAudit::getId)
                .toList();
        if (!rejectionAuditIds.isEmpty()) {
            rejectionAuditRepository.deleteAllByIdInBatch(rejectionAuditIds);
        }

        return new PaymentOutboxRetentionResult(
                publishedEventIds.size(),
                recoveryAuditsDeleted,
                deadLetterHandoffsDeleted,
                rejectionAuditIds.size()
        );
    }
}
