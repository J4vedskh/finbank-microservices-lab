package com.banking.payment.api;

import java.util.List;

public record PaymentOutboxDeadLetterHandoffPage(
        List<PaymentOutboxDeadLetterHandoffSummary> handoffs,
        Long nextCursor
) {
    public PaymentOutboxDeadLetterHandoffPage {
        handoffs = List.copyOf(handoffs);
    }
}
