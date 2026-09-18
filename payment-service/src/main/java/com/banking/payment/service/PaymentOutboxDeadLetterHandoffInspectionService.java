package com.banking.payment.service;

import com.banking.payment.api.PaymentOutboxDeadLetterHandoffPage;
import com.banking.payment.api.PaymentOutboxDeadLetterHandoffSummary;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PaymentOutboxDeadLetterHandoffInspectionService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final PaymentOutboxDeadLetterHandoffRepository handoffRepository;

    public PaymentOutboxDeadLetterHandoffInspectionService(
            PaymentOutboxDeadLetterHandoffRepository handoffRepository
    ) {
        this.handoffRepository = handoffRepository;
    }

    @Transactional(readOnly = true)
    public PaymentOutboxDeadLetterHandoffPage list(Long afterId, int limit) {
        validateRequest(afterId, limit);
        List<PaymentOutboxDeadLetterHandoffSummary> fetched = handoffRepository
                .findSummariesAfterId(afterId, PageRequest.of(0, limit + 1));
        boolean hasMore = fetched.size() > limit;
        List<PaymentOutboxDeadLetterHandoffSummary> handoffs = fetched.stream()
                .limit(limit)
                .toList();
        Long nextCursor = hasMore
                ? handoffs.get(handoffs.size() - 1).handoffId()
                : null;
        return new PaymentOutboxDeadLetterHandoffPage(handoffs, nextCursor);
    }

    private void validateRequest(Long afterId, int limit) {
        if (afterId != null && afterId <= 0) {
            throw new IllegalArgumentException("handoff cursor must be positive");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "handoff inspection limit must be between 1 and " + MAX_LIMIT
            );
        }
    }
}
