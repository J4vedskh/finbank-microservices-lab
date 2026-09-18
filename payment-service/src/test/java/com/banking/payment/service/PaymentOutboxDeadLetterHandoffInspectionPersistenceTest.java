package com.banking.payment.service;

import com.banking.payment.api.PaymentOutboxDeadLetterHandoffPage;
import com.banking.payment.api.PaymentOutboxDeadLetterHandoffSummary;
import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxDeadLetterHandoff;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.repository.PaymentOutboxDeadLetterHandoffRepository;
import com.banking.payment.repository.PaymentOutboxRepository;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(PaymentOutboxDeadLetterHandoffInspectionService.class)
class PaymentOutboxDeadLetterHandoffInspectionPersistenceTest {
    private static final String PAYLOAD_SENTINEL = "PRIVATE-PAYLOAD-MUST-NOT-BE-RETURNED";

    @Autowired
    private PaymentOutboxDeadLetterHandoffInspectionService inspectionService;

    @Autowired
    private PaymentOutboxDeadLetterHandoffRepository handoffRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void list_projectsOnlySafeFieldsWithStableBoundedCursorOrdering() {
        PaymentOutboxDeadLetterHandoff first = persistHandoff(1, "TimeoutException");
        PaymentOutboxDeadLetterHandoff second = persistHandoff(2, "IllegalStateException");
        PaymentOutboxDeadLetterHandoff third = persistHandoff(3, "TimeoutException");
        long handoffCount = handoffRepository.count();
        long outboxCount = outboxRepository.count();

        PaymentOutboxDeadLetterHandoffPage firstPage = inspectionService.list(null, 2);
        PaymentOutboxDeadLetterHandoffPage secondPage = inspectionService.list(
                firstPage.nextCursor(),
                2
        );

        assertThat(firstPage.handoffs())
                .extracting(PaymentOutboxDeadLetterHandoffSummary::handoffId)
                .containsExactly(first.getId(), second.getId());
        assertThat(firstPage.nextCursor()).isEqualTo(second.getId());
        assertThat(secondPage.handoffs())
                .extracting(PaymentOutboxDeadLetterHandoffSummary::handoffId)
                .containsExactly(third.getId());
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(firstPage.handoffs().get(0).eventId())
                .isEqualTo(first.getOutboxEvent().getId());
        assertThat(firstPage.handoffs().get(0).failureType())
                .isEqualTo("TimeoutException");
        assertThat(firstPage.handoffs().toString()).doesNotContain(PAYLOAD_SENTINEL);
        assertThat(Arrays.stream(
                PaymentOutboxDeadLetterHandoffSummary.class.getRecordComponents()
        ).map(component -> component.getName())).containsExactly(
                "handoffId",
                "eventId",
                "exhaustionSequence",
                "exhaustedAt",
                "attemptCount",
                "failureType"
        );
        assertThat(handoffRepository.count()).isEqualTo(handoffCount);
        assertThat(outboxRepository.count()).isEqualTo(outboxCount);
    }

    private PaymentOutboxDeadLetterHandoff persistHandoff(
            int fixture,
            String failureType
    ) {
        Payment payment = new Payment();
        payment.setIdempotencyKeyHash(String.format("%064d", fixture));
        payment.setFromAccount(100L + fixture);
        payment.setToAccount(200L + fixture);
        payment.setAmount(new BigDecimal("125.00"));
        payment.setStatus("PUBLISH_EXHAUSTED");
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        Instant createdAt = Instant.parse("2026-09-18T05:30:00Z")
                .minusSeconds(100 - fixture)
                .truncatedTo(ChronoUnit.MICROS);
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                savedPayment,
                "payments",
                "private-key-" + fixture,
                PAYLOAD_SENTINEL + "-" + fixture,
                createdAt
        );
        event.markExhausted(createdAt.plusSeconds(10), failureType);
        PaymentOutboxEvent savedEvent = outboxRepository.saveAndFlush(event);
        return handoffRepository.saveAndFlush(
                new PaymentOutboxDeadLetterHandoff(savedEvent)
        );
    }
}
