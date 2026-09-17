package com.banking.payment.service;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryRejectionAudit;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryAuditRepository;
import com.banking.payment.repository.PaymentOutboxRecoveryRejectionAuditRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentOutboxRetentionTransactionTest {
    private static final Instant PUBLISHED_CUTOFF = Instant.parse("2026-08-17T05:30:00Z");
    private static final Instant REJECTION_CUTOFF = Instant.parse("2026-08-27T05:30:00Z");

    private final PaymentOutboxRepository outboxRepository =
            mock(PaymentOutboxRepository.class);
    private final PaymentOutboxDeadLetterHandoffRepository deadLetterHandoffRepository =
            mock(PaymentOutboxDeadLetterHandoffRepository.class);
    private final PaymentOutboxRecoveryAuditRepository recoveryAuditRepository =
            mock(PaymentOutboxRecoveryAuditRepository.class);
    private final PaymentOutboxRecoveryRejectionAuditRepository rejectionAuditRepository =
            mock(PaymentOutboxRecoveryRejectionAuditRepository.class);
    private final PaymentOutboxRetentionTransaction transaction =
            new PaymentOutboxRetentionTransaction(
                    outboxRepository,
                    deadLetterHandoffRepository,
                    recoveryAuditRepository,
                    rejectionAuditRepository
            );

    @Test
    void purgeExpired_deletesLockedCandidatesInForeignKeySafeOrder() {
        PaymentOutboxEvent firstEvent = event(11L);
        PaymentOutboxEvent secondEvent = event(12L);
        PaymentOutboxRecoveryRejectionAudit rejection = rejection(21L);
        PageRequest batch = PageRequest.of(0, 2);
        when(outboxRepository
                .findByStatusAndPublishedAtLessThanOrderByPublishedAtAscIdAsc(
                        PaymentOutboxStatus.PUBLISHED,
                        PUBLISHED_CUTOFF,
                        batch
                )).thenReturn(List.of(firstEvent, secondEvent));
        when(recoveryAuditRepository.deleteByOutboxEventIdIn(List.of(11L, 12L)))
                .thenReturn(3);
        when(deadLetterHandoffRepository.deleteByOutboxEventIdIn(List.of(11L, 12L)))
                .thenReturn(4);
        when(rejectionAuditRepository
                .findByRejectedAtLessThanOrderByRejectedAtAscIdAsc(
                        REJECTION_CUTOFF,
                        batch
                )).thenReturn(List.of(rejection));

        PaymentOutboxRetentionResult result = transaction.purgeExpired(
                PUBLISHED_CUTOFF,
                REJECTION_CUTOFF,
                2
        );

        assertThat(result).isEqualTo(new PaymentOutboxRetentionResult(2, 3, 4, 1));
        InOrder deletionOrder = inOrder(
                recoveryAuditRepository,
                deadLetterHandoffRepository,
                outboxRepository,
                rejectionAuditRepository
        );
        deletionOrder.verify(recoveryAuditRepository)
                .deleteByOutboxEventIdIn(List.of(11L, 12L));
        deletionOrder.verify(deadLetterHandoffRepository)
                .deleteByOutboxEventIdIn(List.of(11L, 12L));
        deletionOrder.verify(outboxRepository).deleteAllByIdInBatch(List.of(11L, 12L));
        deletionOrder.verify(rejectionAuditRepository).deleteAllByIdInBatch(List.of(21L));
    }

    @Test
    void purgeExpired_emptyBatchesDoNotIssueDeletes() {
        PageRequest batch = PageRequest.of(0, 100);
        when(outboxRepository
                .findByStatusAndPublishedAtLessThanOrderByPublishedAtAscIdAsc(
                        PaymentOutboxStatus.PUBLISHED,
                        PUBLISHED_CUTOFF,
                        batch
                )).thenReturn(List.of());
        when(rejectionAuditRepository
                .findByRejectedAtLessThanOrderByRejectedAtAscIdAsc(
                        REJECTION_CUTOFF,
                        batch
                )).thenReturn(List.of());

        PaymentOutboxRetentionResult result = transaction.purgeExpired(
                PUBLISHED_CUTOFF,
                REJECTION_CUTOFF,
                100
        );

        assertThat(result).isEqualTo(new PaymentOutboxRetentionResult(0, 0, 0, 0));
        verify(recoveryAuditRepository, never()).deleteByOutboxEventIdIn(any());
        verify(deadLetterHandoffRepository, never()).deleteByOutboxEventIdIn(any());
        verify(outboxRepository, never()).deleteAllByIdInBatch(any());
        verify(rejectionAuditRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    void purgeExpired_recoveryAuditDeleteFailureDoesNotAttemptParentOrRejectionDelete() {
        PaymentOutboxEvent event = event(11L);
        PageRequest batch = PageRequest.of(0, 10);
        when(outboxRepository
                .findByStatusAndPublishedAtLessThanOrderByPublishedAtAscIdAsc(
                        PaymentOutboxStatus.PUBLISHED,
                        PUBLISHED_CUTOFF,
                        batch
                )).thenReturn(List.of(event));
        when(recoveryAuditRepository.deleteByOutboxEventIdIn(List.of(11L)))
                .thenThrow(new DataIntegrityViolationException("audit delete failed"));

        assertThatThrownBy(() -> transaction.purgeExpired(
                PUBLISHED_CUTOFF,
                REJECTION_CUTOFF,
                10
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("audit delete failed");

        verify(outboxRepository, never()).deleteAllByIdInBatch(any());
        verifyNoInteractions(deadLetterHandoffRepository, rejectionAuditRepository);
    }

    private PaymentOutboxEvent event(Long id) {
        PaymentOutboxEvent event = mock(PaymentOutboxEvent.class);
        when(event.getId()).thenReturn(id);
        return event;
    }

    private PaymentOutboxRecoveryRejectionAudit rejection(Long id) {
        PaymentOutboxRecoveryRejectionAudit audit =
                mock(PaymentOutboxRecoveryRejectionAudit.class);
        when(audit.getId()).thenReturn(id);
        return audit;
    }
}
