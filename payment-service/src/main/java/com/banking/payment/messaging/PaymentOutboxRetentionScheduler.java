package com.banking.payment.messaging;

import com.banking.payment.service.PaymentOutboxRetentionResult;
import com.banking.payment.service.PaymentOutboxRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "payment.outbox.retention",
        name = "enabled",
        havingValue = "true"
)
public class PaymentOutboxRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PaymentOutboxRetentionScheduler.class
    );

    private final PaymentOutboxRetentionService retentionService;

    public PaymentOutboxRetentionScheduler(PaymentOutboxRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(
            fixedDelayString = "${payment.outbox.retention.cleanup-delay-ms:86400000}"
    )
    public void purgeExpired() {
        PaymentOutboxRetentionResult result = retentionService.runOnce();
        if (result.totalDeleted() > 0) {
            LOGGER.info(
                    "Outbox retention deleted {} published events, "
                            + "{} recovery audits, and {} rejection audits",
                    result.publishedEventsDeleted(),
                    result.recoveryAuditsDeleted(),
                    result.rejectionAuditsDeleted()
            );
        }
    }
}
