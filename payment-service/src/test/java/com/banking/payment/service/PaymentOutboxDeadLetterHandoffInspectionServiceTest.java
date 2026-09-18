package com.banking.payment.service;

import com.banking.payment.api.PaymentOutboxDeadLetterHandoffPage;
import com.banking.payment.api.PaymentOutboxDeadLetterHandoffSummary;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PaymentOutboxDeadLetterHandoffInspectionServiceTest {
    private final PaymentOutboxDeadLetterHandoffRepository handoffRepository =
            mock(PaymentOutboxDeadLetterHandoffRepository.class);
    private final PaymentOutboxDeadLetterHandoffInspectionService inspectionService =
            new PaymentOutboxDeadLetterHandoffInspectionService(handoffRepository);

    @Test
    void list_usesExclusiveCursorAndLimitPlusOneWithoutCounting() {
        PaymentOutboxDeadLetterHandoffSummary first = summary(41L);
        PaymentOutboxDeadLetterHandoffSummary second = summary(42L);
        PaymentOutboxDeadLetterHandoffSummary extra = summary(43L);
        when(handoffRepository.findSummariesAfterId(40L, PageRequest.of(0, 3)))
                .thenReturn(List.of(first, second, extra));

        PaymentOutboxDeadLetterHandoffPage page = inspectionService.list(40L, 2);

        assertThat(page.handoffs()).containsExactly(first, second);
        assertThat(page.nextCursor()).isEqualTo(42L);
        verify(handoffRepository)
                .findSummariesAfterId(40L, PageRequest.of(0, 3));
        verifyNoMoreInteractions(handoffRepository);
    }

    @Test
    void list_finalPageHasNoCursorAndOwnsAnImmutableCopy() {
        PaymentOutboxDeadLetterHandoffSummary only = summary(41L);
        when(handoffRepository.findSummariesAfterId(null, PageRequest.of(0, 3)))
                .thenReturn(List.of(only));

        PaymentOutboxDeadLetterHandoffPage page = inspectionService.list(null, 2);

        assertThat(page.handoffs()).containsExactly(only);
        assertThat(page.nextCursor()).isNull();
        assertThatThrownBy(() -> page.handoffs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void list_rejectsInvalidCursorAndLimitsBeforeRepositoryAccess() {
        assertThatThrownBy(() -> inspectionService.list(0L, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inspectionService.list(-1L, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inspectionService.list(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inspectionService.list(null, 101))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(handoffRepository);
    }

    private PaymentOutboxDeadLetterHandoffSummary summary(Long id) {
        return new PaymentOutboxDeadLetterHandoffSummary(
                id,
                id + 100,
                1,
                Instant.parse("2026-09-17T05:30:00Z").plusSeconds(id),
                5,
                "TimeoutException"
        );
    }
}
